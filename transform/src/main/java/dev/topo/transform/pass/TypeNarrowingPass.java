package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Replaces {@code Array.get(i)} virtual calls with direct {@code rawData()[i]} access.
 *
 * <p>Detection: {@code INVOKEVIRTUAL Array.get(I)Object → CHECKCAST T}
 * <br>Rewrite: {@code INVOKEVIRTUAL Array.rawData()[Object → ILOAD i → AALOAD → CHECKCAST T}</p>
 *
 * <p><strong>Note:</strong> This pass is force-only. HotSpot C2 already devirtualizes
 * monomorphic {@code Array.get()} after warmup via speculative inlining — the AOT
 * bytecode rewrite provides no measurable benefit over C2's runtime optimization.
 * Retained for correctness testing and scenarios where AOT guarantees are preferred
 * over JIT speculation.</p>
 */
// @category: COVERED
public class TypeNarrowingPass implements BasePass {
    private static final String ARRAY_INTERNAL = "dev/topo/Array";
    private static final String ARRAY_GET_NAME = "get";
    private static final String ARRAY_GET_DESC = "(I)Ljava/lang/Object;";
    private static final String RAW_DATA_NAME = "rawData";
    private static final String RAW_DATA_DESC = "()[Ljava/lang/Object;";

    private final JsonObject config;
    private final JsonObject metadata;
    private final String mode;
    /** host_method qualified name → count of `Array.get()` sites narrowed
     *  to `rawData()+AALOAD`. Sidecar schema:
     *  `narrowed_sites:[{host_method, count}]`. */
    private final Map<String, Integer> narrowedByMethod = new LinkedHashMap<>();

    public TypeNarrowingPass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.mode = extractMode();
    }

    /** Per-method narrowed-site counts aggregated across classes. */
    public Map<String, Integer> getNarrowedByMethod() {
        return narrowedByMethod;
    }

    private String extractMode() {
        if (config.has("typeNarrowingCfg")) {
            var cfg = config.getAsJsonObject("typeNarrowingCfg");
            if (cfg.has("mode")) return cfg.get("mode").getAsString();
        }
        return "off";
    }

    @Override
    public ClassVisitor createVisitor(ClassWriter writer) {
        if ("off".equals(mode)) {
            return new ClassVisitor(Opcodes.ASM9, writer) {};
        }

        return new ClassVisitor(Opcodes.ASM9, writer) {
            private String className;

            @Override
            public void visit(int version, int access, String name, String signature,
                             String superName, String[] interfaces) {
                this.className = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                MethodVisitor downstream = super.visitMethod(access, name, descriptor,
                        signature, exceptions);

                // Skip constructors and static initializers
                if ("<init>".equals(name) || "<clinit>".equals(name)) {
                    return downstream;
                }

                String hostMethod = className.replace("/", "::") + "::" + name;
                return new TypeNarrowingMethodNode(Opcodes.ASM9, access, name, descriptor,
                        signature, exceptions != null ? exceptions : new String[0],
                        downstream, hostMethod, TypeNarrowingPass.this);
            }
        };
    }

    private static class TypeNarrowingMethodNode extends MethodNode {
        private final MethodVisitor downstream;
        private final String hostMethod;
        private final TypeNarrowingPass outer;

        TypeNarrowingMethodNode(int api, int access, String name, String descriptor,
                               String signature, String[] exceptions,
                               MethodVisitor downstream, String hostMethod,
                               TypeNarrowingPass outer) {
            super(api, access, name, descriptor, signature, exceptions);
            this.downstream = downstream;
            this.hostMethod = hostMethod;
            this.outer = outer;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            transform();
            accept(downstream);
        }

        private void transform() {
            // Find all Array.get(i) → CHECKCAST sequences and rewrite them
            List<NarrowingSite> sites = detect();
            if (sites.isEmpty()) return;

            // Rewrite in reverse order to preserve instruction indices
            for (int i = sites.size() - 1; i >= 0; i--) {
                rewrite(sites.get(i));
            }
            outer.narrowedByMethod.merge(hostMethod, sites.size(), Integer::sum);
        }

        private List<NarrowingSite> detect() {
            List<NarrowingSite> sites = new ArrayList<>();
            AbstractInsnNode[] insns = instructions.toArray();

            for (int i = 0; i < insns.length; i++) {
                // Look for INVOKEVIRTUAL Array.get
                if (!(insns[i] instanceof MethodInsnNode mi)) continue;
                if (mi.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                if (!ARRAY_INTERNAL.equals(mi.owner)) continue;
                if (!ARRAY_GET_NAME.equals(mi.name)) continue;
                if (!ARRAY_GET_DESC.equals(mi.desc)) continue;

                // Next real instruction should be CHECKCAST
                AbstractInsnNode next = skipNonCode(insns, i + 1);
                if (next == null) continue;
                if (!(next instanceof TypeInsnNode cast)) continue;
                if (cast.getOpcode() != Opcodes.CHECKCAST) continue;

                // Find the index load instruction (right before INVOKEVIRTUAL).
                // The rewrite clones this single instruction to re-supply the
                // index after rawData(); that is only correct when the index is
                // a SELF-CONTAINED value push (consumes nothing, pushes exactly
                // one int). A field-backed index (`this.idx` →
                // ALOAD this; GETFIELD idx) is NOT self-contained: the GETFIELD
                // consumes the `ALOAD this` pushed just before it. The old code
                // then mistook that `ALOAD this` for the array ref, emitted
                // rawData() on `this`, and orphaned the real array load —
                // type-incorrect, stack-imbalanced bytecode. Decline unless the
                // index is a single self-contained int producer.
                AbstractInsnNode indexInsn = findPrevCode(insns, i - 1);
                if (indexInsn == null) continue;
                if (!isSelfContainedIndex(indexInsn)) continue;

                // Find the instruction that pushes the Array ref (immediately
                // before the index). Only a self-contained reference push —
                // ALOAD (local) or GETSTATIC (static field) — can be cloned in
                // isolation. A GETFIELD array ref would need its preceding
                // object-ref load re-materialized too, which a single-node
                // clone cannot do, so it is declined as well.
                AbstractInsnNode arrayLoadInsn = findPrevCode(insns, indexOf(insns, indexInsn) - 1);
                if (arrayLoadInsn == null) continue;
                if (!isSelfContainedArrayRefLoad(arrayLoadInsn)) continue;

                sites.add(new NarrowingSite(arrayLoadInsn, indexInsn, mi, cast));
            }

            return sites;
        }

        private void rewrite(NarrowingSite site) {
            // Replace: <arrayRef>, ILOAD idx, INVOKEVIRTUAL Array.get → CHECKCAST T
            // With:    <arrayRef>, INVOKEVIRTUAL Array.rawData, ILOAD idx, AALOAD, CHECKCAST T

            InsnList replacement = new InsnList();

            // Clone the array ref load (ALOAD, GETSTATIC, or GETFIELD)
            replacement.add(site.arrayLoad.clone(null));

            // INVOKEVIRTUAL Array.rawData ()[Ljava/lang/Object;
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    ARRAY_INTERNAL, RAW_DATA_NAME, RAW_DATA_DESC, false));

            // Clone the index load instruction
            replacement.add(site.indexLoad.clone(null));

            // AALOAD
            replacement.add(new InsnNode(Opcodes.AALOAD));

            // CHECKCAST (preserved as type guard)
            replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, site.castInsn.desc));

            // Insert replacement after the CHECKCAST, then remove originals
            instructions.insert(site.castInsn, replacement);

            instructions.remove(site.arrayLoad);
            instructions.remove(site.indexLoad);
            instructions.remove(site.getInsn);
            instructions.remove(site.castInsn);
        }

        /**
         * True for a single instruction that pushes the array reference and
         * consumes nothing — safe to clone in isolation. ALOAD reads a local
         * and GETSTATIC reads a static field; both leave the stack below them
         * untouched. GETFIELD is deliberately excluded: it consumes an
         * object-ref that a single-node clone cannot reproduce.
         */
        private static boolean isSelfContainedArrayRefLoad(AbstractInsnNode insn) {
            if (insn instanceof VarInsnNode vi && vi.getOpcode() == Opcodes.ALOAD) return true;
            if (insn instanceof FieldInsnNode fi) {
                return fi.getOpcode() == Opcodes.GETSTATIC;
            }
            return false;
        }

        /**
         * True for a single instruction that pushes the int index and consumes
         * nothing — safe to clone in isolation after rawData(). Covers ILOAD
         * and the int-constant pushes (ICONST_*, BIPUSH, SIPUSH, and an LDC of
         * an Integer). A composite index expression (e.g. a field load or
         * arithmetic) is declined because cloning only the last instruction
         * would drop the operands it depends on.
         */
        private static boolean isSelfContainedIndex(AbstractInsnNode insn) {
            int op = insn.getOpcode();
            if (op == Opcodes.ILOAD) return true;
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return true;
            if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return true;
            if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Integer) return true;
            return false;
        }

        // Utility methods
        private static AbstractInsnNode skipNonCode(AbstractInsnNode[] insns, int startIdx) {
            for (int i = startIdx; i < insns.length; i++) {
                if (insns[i].getOpcode() >= 0) return insns[i];
            }
            return null;
        }

        private static AbstractInsnNode findPrevCode(AbstractInsnNode[] insns, int startIdx) {
            for (int i = startIdx; i >= 0; i--) {
                if (insns[i].getOpcode() >= 0) return insns[i];
            }
            return null;
        }

        private static int indexOf(AbstractInsnNode[] insns, AbstractInsnNode target) {
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] == target) return i;
            }
            return -1;
        }
    }

    private static class NarrowingSite {
        final AbstractInsnNode arrayLoad; // ALOAD, GETSTATIC, or GETFIELD
        final AbstractInsnNode indexLoad; // ILOAD index (or other index expression)
        final MethodInsnNode getInsn;    // INVOKEVIRTUAL Array.get
        final TypeInsnNode castInsn;     // CHECKCAST T

        NarrowingSite(AbstractInsnNode arrayLoad, AbstractInsnNode indexLoad,
                     MethodInsnNode getInsn, TypeInsnNode castInsn) {
            this.arrayLoad = arrayLoad;
            this.indexLoad = indexLoad;
            this.getInsn = getInsn;
            this.castInsn = castInsn;
        }
    }
}
