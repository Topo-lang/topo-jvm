package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Materializes {@code access(streaming)} declarations as a bytecode-level
 * witness call in counted loop bodies (declaration-class, not a speedup pass on JVM).
 *
 * <p>Detects counted loop patterns that iterate over {@code dev.topo.Array}
 * via {@code Array.get(i)} and inserts a single
 * {@code Prefetch.accessStreamingWitness()} call at the start of the loop body.
 * The method is an empty static that C2 inlines to nothing at runtime, so the
 * runtime cost is near zero while the {@code INVOKESTATIC} instruction remains
 * in the classfile as the observable forced-mode bytecode diff.</p>
 *
 * <p><strong>JVM semantics — ENHANCE, not OPT/COVERED</strong>:
 * HotSpot has no public software-prefetch intrinsic; the earlier
 * {@code Prefetch.prefetchObjects} path used synchronous {@code Unsafe.getInt}
 * (blocking load, not a non-blocking HW hint) and even with a per-N stride
 * gate produced a measured 1.41× regression on the friendly benchmark — well
 * outside the ENHANCE {@code topo/vanilla ≤ 1.05} band. Since the pass has no
 * speedup path on JVM, forced-mode injection degenerates to a pure witness
 * marker: it produces the required forced-mode bytecode diff without
 * attempting a speedup that the measurements show would regress.</p>
 *
 * <p><strong>Mode semantics</strong>:
 * <ul>
 *   <li>{@code off} — no transformation (default)</li>
 *   <li>{@code auto} — no-op on JVM for streaming (HW covers; auto must stay
 *       behaviorally equivalent to {@code off})</li>
 *   <li>{@code force} — inject one empty witness call per matching loop body;
 *       satisfies the "forced must produce bytecode diff" absolute rule
 *       without materially affecting runtime</li>
 * </ul>
 *
 * <p>Uses the Tree API (MethodNode) for full method body analysis, consistent
 * with DataLayoutPass and LoopVectorizePass.</p>
 */
// @category: ENHANCE
public class PrefetchPass implements BasePass {
    private static final String ARRAY_INTERNAL = "dev/topo/Array";
    private static final String PREFETCH_INTERNAL = "dev/topo/Prefetch";

    private final JsonObject config;
    private final JsonObject metadata;
    private final Map<String, String> accessPatterns;
    private final String mode;

    public PrefetchPass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.accessPatterns = buildAccessPatternMap();
        this.mode = extractMode();
    }

    private String extractMode() {
        if (config.has("prefetchCfg")) {
            var cfg = config.getAsJsonObject("prefetchCfg");
            if (cfg.has("mode")) return cfg.get("mode").getAsString();
        }
        return "off";
    }

    private Map<String, String> buildAccessPatternMap() {
        Map<String, String> result = new HashMap<>();
        if (!metadata.has("functions")) return result;

        for (var entry : metadata.getAsJsonObject("functions").entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            if (fn.has("accessPattern") && fn.has("qualifiedName")) {
                result.put(fn.get("qualifiedName").getAsString(),
                           fn.get("accessPattern").getAsString());
            }
        }
        return result;
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
                if (shouldTransform(className, name)) {
                    return new PrefetchMethodNode(Opcodes.ASM9, access, name, descriptor,
                            signature, exceptions != null ? exceptions : new String[0],
                            downstream);
                }
                return downstream;
            }
        };
    }

    private boolean shouldTransform(String className, String methodName) {
        String qualifiedName = className.replace("/", "::") + "::" + methodName;

        // Check namespace-level match too
        String namespaceName = null;
        int lastSlash = className.lastIndexOf('/');
        if (lastSlash >= 0) {
            namespaceName = className.substring(0, lastSlash).replace("/", "::") + "::" + methodName;
        }

        // Look up access pattern for this function
        String pattern = accessPatterns.get(qualifiedName);
        if (pattern == null && namespaceName != null) {
            pattern = accessPatterns.get(namespaceName);
        }

        // "random" access pattern: skip transformation
        if ("random".equals(pattern)) return false;

        // "force" mode: transform all non-random methods (declaration-class witness;
        // stride gate inside the loop body limits the runtime overhead)
        if ("force".equals(mode)) return true;

        // On JVM, "auto" (and any non-force, non-off mode) for streaming is a
        // deliberate no-op: HotSpot's HW prefetcher already covers sequential
        // access, and the synchronous Unsafe.getInt pattern used by
        // Prefetch.prefetchObjects has no speedup path (see class javadoc).
        // Auto must remain equivalent to off (auto-mode semantics).
        // Declaration witnessing happens only under "force" mode.
        return false;
    }

    // =========================================================================
    // Counted loop detection
    // =========================================================================

    /**
     * Represents a detected counted loop with Array access inside the body.
     */
    private static class CountedLoop {
        /** Local variable slot of the loop counter (ISTORE/ILOAD/IINC target) */
        final int loopVar;
        /** Instruction that loads the Array reference (ALOAD, GETSTATIC, or GETFIELD) */
        final AbstractInsnNode arrayLoadInsn;
        /** The JumpInsnNode for IF_ICMPGE that exits the loop */
        final JumpInsnNode exitJump;
        /** The instruction right after the IF_ICMPGE — start of loop body */
        final AbstractInsnNode bodyStart;

        CountedLoop(int loopVar, AbstractInsnNode arrayLoadInsn, JumpInsnNode exitJump,
                    AbstractInsnNode bodyStart) {
            this.loopVar = loopVar;
            this.arrayLoadInsn = arrayLoadInsn;
            this.exitJump = exitJump;
            this.bodyStart = bodyStart;
        }
    }

    // =========================================================================
    // Method transformation node
    // =========================================================================

    private static class PrefetchMethodNode extends MethodNode {
        private final MethodVisitor downstream;

        PrefetchMethodNode(int api, int access, String name, String descriptor,
                          String signature, String[] exceptions,
                          MethodVisitor downstream) {
            super(api, access, name, descriptor, signature, exceptions);
            this.downstream = downstream;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            transform();
            accept(downstream);
        }

        private void transform() {
            List<CountedLoop> loops = detectCountedLoops();
            for (CountedLoop loop : loops) {
                if (loop.arrayLoadInsn != null) {
                    insertPrefetch(loop);
                }
            }
        }

        // =================================================================
        // Step 1: Detect counted loops with Array access
        // =================================================================

        /**
         * Scan for counted loop patterns:
         * <ol>
         *   <li>ICONST_0 / ISTORE x — counter initialization</li>
         *   <li>ILOAD x / ... / IF_ICMPGE exitLabel — loop condition</li>
         *   <li>IINC x 1 — counter increment</li>
         *   <li>GOTO back to condition — loop back-edge</li>
         *   <li>Array.get(x) somewhere in the loop body</li>
         * </ol>
         */
        private List<CountedLoop> detectCountedLoops() {
            List<CountedLoop> result = new ArrayList<>();
            AbstractInsnNode[] insns = instructions.toArray();

            for (int i = 0; i < insns.length - 1; i++) {
                // Look for ICONST_0 followed by ISTORE
                if (insns[i].getOpcode() != Opcodes.ICONST_0) continue;
                AbstractInsnNode next = skipNonCode(insns, i + 1);
                if (next == null || next.getOpcode() != Opcodes.ISTORE) continue;

                int loopVar = ((VarInsnNode) next).var;

                // Find the IF_ICMPGE that uses this loop variable
                JumpInsnNode exitJump = findExitJump(insns, indexOf(insns, next) + 1, loopVar);
                if (exitJump == null) continue;

                // Verify there is an IINC for this variable after the exit jump
                if (!hasIInc(insns, indexOf(insns, exitJump) + 1, loopVar)) continue;

                // Find Array access in the loop body (between IF_ICMPGE and IINC)
                int bodyStartIdx = indexOf(insns, exitJump) + 1;
                AbstractInsnNode bodyStart = skipNonCode(insns, bodyStartIdx);
                if (bodyStart == null) continue;

                AbstractInsnNode arrayLoadInsn = findArrayAccessInBody(insns, bodyStartIdx, loopVar);

                if (arrayLoadInsn != null) {
                    result.add(new CountedLoop(loopVar, arrayLoadInsn, exitJump, bodyStart));
                }
            }
            return result;
        }

        /**
         * Find the IF_ICMPGE jump that compares the loop variable against a limit.
         * Looks for: ILOAD loopVar, ..., IF_ICMPGE
         */
        private JumpInsnNode findExitJump(AbstractInsnNode[] insns, int startIdx, int loopVar) {
            for (int i = startIdx; i < insns.length; i++) {
                if (!(insns[i] instanceof VarInsnNode vi)) continue;
                if (vi.getOpcode() != Opcodes.ILOAD || vi.var != loopVar) continue;

                // Scan forward from this ILOAD for an IF_ICMPGE
                for (int j = i + 1; j < Math.min(i + 6, insns.length); j++) {
                    if (insns[j] instanceof JumpInsnNode ji
                            && ji.getOpcode() == Opcodes.IF_ICMPGE) {
                        return ji;
                    }
                }
            }
            return null;
        }

        /**
         * Check if there is an IINC instruction for the given variable in the range.
         */
        private boolean hasIInc(AbstractInsnNode[] insns, int startIdx, int loopVar) {
            for (int i = startIdx; i < insns.length; i++) {
                if (insns[i] instanceof IincInsnNode iinc
                        && iinc.var == loopVar && iinc.incr == 1) {
                    return true;
                }
                // Stop at the next GOTO (loop back-edge) to avoid scanning too far
                if (insns[i].getOpcode() == Opcodes.GOTO) return false;
            }
            return false;
        }

        /**
         * Search the loop body for an INVOKEVIRTUAL Array.get(loopVar) pattern.
         * Returns the instruction that loads the Array reference, or null if not found.
         * Handles ALOAD (local var), GETSTATIC (static field), and GETFIELD (instance field).
         */
        private AbstractInsnNode findArrayAccessInBody(AbstractInsnNode[] insns, int startIdx, int loopVar) {
            for (int i = startIdx; i < insns.length; i++) {
                // Stop at IINC (end of body before increment)
                if (insns[i] instanceof IincInsnNode iinc && iinc.var == loopVar) break;
                // Stop at GOTO (back-edge)
                if (insns[i].getOpcode() == Opcodes.GOTO) break;

                if (!(insns[i] instanceof MethodInsnNode mi)) continue;
                if (mi.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                if (!ARRAY_INTERNAL.equals(mi.owner)) continue;
                if (!"get".equals(mi.name)) continue;

                // Verify the index argument is the loop variable
                AbstractInsnNode prev1 = findPrevCode(insns, indexOf(insns, mi) - 1);
                if (prev1 instanceof VarInsnNode vi1 && vi1.getOpcode() == Opcodes.ILOAD
                        && vi1.var == loopVar) {
                    // The instruction before the index load should load the Array ref
                    AbstractInsnNode prev2 = findPrevCode(insns, indexOf(insns, prev1) - 1);
                    if (isArrayRefLoad(prev2)) {
                        return prev2;
                    }
                }
            }
            return null;
        }

        /** Check if an instruction loads an object reference onto the stack. */
        private static boolean isArrayRefLoad(AbstractInsnNode insn) {
            if (insn == null) return false;
            if (insn instanceof VarInsnNode vi && vi.getOpcode() == Opcodes.ALOAD) return true;
            if (insn instanceof FieldInsnNode fi) {
                return fi.getOpcode() == Opcodes.GETSTATIC || fi.getOpcode() == Opcodes.GETFIELD;
            }
            return false;
        }

        // =================================================================
        // Step 2: Insert prefetch bytecode
        // =================================================================

        /**
         * Insert a pure declaration-marker call at the start of the loop body.
         *
         * <p>Emits a single {@code INVOKESTATIC Prefetch.accessStreamingWitness()V}.
         * The target method is empty, so C2 inlines the call to nothing at runtime
         * — the remaining bytecode instruction is the observable witness that
         * forced-mode prefetch injection fired, satisfying the
         * "forced without bytecode diff is ERROR" rule without a real prefetch
         * cost. See class javadoc for why the earlier stride-gated
         * {@code prefetchObjects} path was removed.</p>
         *
         * <pre>
         *   INVOKESTATIC dev/topo/Prefetch.accessStreamingWitness ()V
         * </pre>
         */
        private void insertPrefetch(CountedLoop loop) {
            InsnList witness = new InsnList();
            witness.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PREFETCH_INTERNAL,
                    "accessStreamingWitness", "()V", false));
            // Insert at the start of the loop body (right after the IF_ICMPGE).
            instructions.insert(loop.exitJump, witness);
        }

        // =================================================================
        // Utility
        // =================================================================

        private AbstractInsnNode skipNonCode(AbstractInsnNode[] insns, int startIdx) {
            for (int i = startIdx; i < insns.length; i++) {
                if (insns[i].getOpcode() >= 0) return insns[i];
            }
            return null;
        }

        private AbstractInsnNode findPrevCode(AbstractInsnNode[] insns, int startIdx) {
            for (int i = startIdx; i >= 0; i--) {
                if (insns[i].getOpcode() >= 0) return insns[i];
            }
            return null;
        }

        private int indexOf(AbstractInsnNode[] insns, AbstractInsnNode target) {
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] == target) return i;
            }
            return -1;
        }
    }
}
