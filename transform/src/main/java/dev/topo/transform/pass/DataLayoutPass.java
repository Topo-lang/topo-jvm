package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * AoS-to-SoA bytecode transformation for {@code dev.topo.Array<T>} containers.
 *
 * <p>Detects patterns where a method calls {@code Array.get(i)} followed by
 * {@code CHECKCAST T} and {@code GETFIELD T.field} for primitive fields, then
 * rewrites those accesses to use flat column arrays instead.</p>
 *
 * <p>This pass uses the Tree API (MethodNode) to analyse the full method body
 * before rewriting instruction sequences.</p>
 */
// @category: COVERED
public class DataLayoutPass implements BasePass {
    private static boolean autoWarningEmitted = false;
    private static final String ARRAY_INTERNAL = "dev/topo/Array";
    private static final String ARRAY_GET_DESC = "(I)Ljava/lang/Object;";
    private static final String ARRAY_SIZE_DESC = "()I";

    private final JsonObject config;
    private final JsonObject metadata;
    /** Maps qualified function name -> access pattern (e.g. "streaming", "random"). */
    private final Map<String, String> accessPatterns;
    private final String mode;
    /** Per (host_type, single-field) AoS→SoA rewrites the Pass actually
     *  applied. JVM DataLayout transformations are per-method-body (the
     *  Array reference is a local), but the user-facing signal is the
     *  element type + field. Sidecar schema:
     *  `field_rename:[{host_type, fields:[{topo_name, jvm_name, descriptor}]}]`.
     *  `jvm_name` reflects the ColumnarView accessor (e.g. `getFloatColumn`)
     *  the Pass routes through, mirroring LLVM's SoA column naming. */
    private final Map<String, Set<JsonObject>> fieldRenamesByType = new LinkedHashMap<>();

    public DataLayoutPass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.accessPatterns = buildAccessPatternMap();
        this.mode = extractMode();
    }

    /** Per element-type field rewrites aggregated across classes. */
    public Map<String, Set<JsonObject>> getFieldRenamesByType() {
        return fieldRenamesByType;
    }

    private String extractMode() {
        if (config.has("dataLayoutCfg")) {
            var cfg = config.getAsJsonObject("dataLayoutCfg");
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
        if ("auto".equals(mode) && !autoWarningEmitted) {
            autoWarningEmitted = true;
            System.err.println("[topo-transform] remark: DataLayoutPass: auto mode is gated (no-op) — use mode=\"force\" to enable AoS-to-SoA transformation");
        }
        if ("off".equals(mode) || "auto".equals(mode)) {
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
                    return new DataLayoutMethodNode(Opcodes.ASM9, access, name, descriptor,
                            signature, exceptions != null ? exceptions : new String[0],
                            downstream, DataLayoutPass.this);
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

        // "force" mode: transform all non-random methods
        if ("force".equals(mode)) return true;

        // "streaming" pattern: transform eagerly
        if ("streaming".equals(pattern)) return true;

        return false;
    }

    // =========================================================================
    // SoA candidate tracking
    // =========================================================================

    private static class FieldAccess {
        final String fieldName;
        final String fieldDesc;

        FieldAccess(String fieldName, String fieldDesc) {
            this.fieldName = fieldName;
            this.fieldDesc = fieldDesc;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FieldAccess fa)) return false;
            return fieldName.equals(fa.fieldName) && fieldDesc.equals(fa.fieldDesc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fieldName, fieldDesc);
        }
    }

    private static class AccessSite {
        /** The INVOKEVIRTUAL Array.get instruction */
        final MethodInsnNode getInsn;
        /** The CHECKCAST instruction */
        final TypeInsnNode castInsn;
        /** The GETFIELD instruction */
        final FieldInsnNode fieldInsn;

        AccessSite(MethodInsnNode getInsn, TypeInsnNode castInsn, FieldInsnNode fieldInsn) {
            this.getInsn = getInsn;
            this.castInsn = castInsn;
            this.fieldInsn = fieldInsn;
        }
    }

    private static class SoACandidate {
        /** Local variable slot holding the Array reference */
        final int arrayLocal;
        /** Internal name of the element class */
        final String elementClass;
        /** Primitive fields accessed on this array */
        final Set<FieldAccess> fields = new LinkedHashSet<>();
        /** All access sites for this candidate */
        final List<AccessSite> sites = new ArrayList<>();

        SoACandidate(int arrayLocal, String elementClass) {
            this.arrayLocal = arrayLocal;
            this.elementClass = elementClass;
        }
    }

    // =========================================================================
    // Method transformation node
    // =========================================================================

    private static class DataLayoutMethodNode extends MethodNode {
        private final MethodVisitor downstream;
        private final DataLayoutPass outer;

        DataLayoutMethodNode(int api, int access, String name, String descriptor,
                            String signature, String[] exceptions,
                            MethodVisitor downstream, DataLayoutPass outer) {
            super(api, access, name, descriptor, signature, exceptions);
            this.downstream = downstream;
            this.outer = outer;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            transform();
            accept(downstream);
        }

        private void transform() {
            List<SoACandidate> candidates = detect();
            if (candidates.isEmpty()) return;

            for (SoACandidate candidate : candidates) {
                // Skip multi-field candidates: scatter overhead negates SoA benefit
                // when multiple fields are accessed. Only single-field sweeps benefit
                // from contiguous column layout on JVM.
                if (candidate.fields.size() != 1) continue;

                // Access-ratio threshold removed. The Pass does
                // not gate on workload characteristics; the old threshold
                // was an embedded cost heuristic that overstepped the
                // pass's mandate. Force mode applies SoA to every
                // single-field candidate; benchmark/JIT determines actual
                // benefit.

                rewrite(candidate);
                // Sidecar record. host_type is the JVM
                // internal name we render in `pkg::Class` form so users
                // recognise it from `.topo`. ColumnarView always returns
                // arrays; jvm_name reflects that as `<field>_column`.
                FieldAccess fa = candidate.fields.iterator().next();
                String hostType = candidate.elementClass.replace("/", "::");
                JsonObject row = new JsonObject();
                row.addProperty("topo_name", fa.fieldName);
                row.addProperty("jvm_name", fa.fieldName + "_column");
                row.addProperty("descriptor", "[" + fa.fieldDesc);
                outer.fieldRenamesByType
                    .computeIfAbsent(hostType, k -> new LinkedHashSet<>())
                    .add(row);
            }
        }

        // =====================================================================
        // Step 1: Detect Array.get(i).field patterns
        // =====================================================================

        private List<SoACandidate> detect() {
            // Group by array local variable
            Map<Integer, SoACandidate> candidatesByLocal = new LinkedHashMap<>();

            AbstractInsnNode[] insns = instructions.toArray();
            for (int i = 0; i < insns.length - 2; i++) {
                // Look for: INVOKEVIRTUAL Array.get -> CHECKCAST T -> GETFIELD T.field
                if (!(insns[i] instanceof MethodInsnNode mi)) continue;
                if (mi.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                if (!ARRAY_INTERNAL.equals(mi.owner)) continue;
                if (!"get".equals(mi.name)) continue;
                if (!ARRAY_GET_DESC.equals(mi.desc)) continue;

                AbstractInsnNode next1 = skipNonCode(insns, i + 1);
                if (next1 == null || !(next1 instanceof TypeInsnNode cast)) continue;
                if (cast.getOpcode() != Opcodes.CHECKCAST) continue;

                AbstractInsnNode next2 = skipNonCode(insns, indexOf(insns, next1) + 1);
                if (next2 == null || !(next2 instanceof FieldInsnNode field)) continue;
                if (field.getOpcode() != Opcodes.GETFIELD) continue;
                if (!field.owner.equals(cast.desc)) continue;

                // Only primitive fields
                if (!isPrimitiveDescriptor(field.desc)) continue;

                // Find which local holds the Array reference by looking backward
                int arrayLocal = findArrayLocal(insns, i);
                if (arrayLocal < 0) continue;

                String elementClass = cast.desc;
                SoACandidate candidate = candidatesByLocal.computeIfAbsent(
                    arrayLocal, k -> new SoACandidate(k, elementClass));

                // Verify element class consistency
                if (!candidate.elementClass.equals(elementClass)) continue;

                FieldAccess fa = new FieldAccess(field.name, field.desc);
                candidate.fields.add(fa);
                candidate.sites.add(new AccessSite(mi, cast, field));
            }

            return new ArrayList<>(candidatesByLocal.values());
        }

        /**
         * Find the ALOAD instruction that pushes the Array reference onto the stack
         * before the get() call.  Walk backward from the INVOKEVIRTUAL, skipping
         * over the index-loading instruction(s).
         */
        private int findArrayLocal(AbstractInsnNode[] insns, int getCallIdx) {
            // The stack before INVOKEVIRTUAL Array.get(I) is: ..., arrayRef, index
            // Walk backward to find what loaded the index, then what loaded arrayRef.
            // Simple case: ALOAD x, ILOAD y, INVOKEVIRTUAL
            int prev1 = findPrevCode(insns, getCallIdx - 1);
            if (prev1 < 0) return -1;

            int prev2 = findPrevCode(insns, prev1 - 1);
            if (prev2 < 0) return -1;

            if (insns[prev2] instanceof VarInsnNode varInsn
                && varInsn.getOpcode() == Opcodes.ALOAD) {
                return varInsn.var;
            }
            return -1;
        }

        private int findPrevCode(AbstractInsnNode[] insns, int startIdx) {
            for (int i = startIdx; i >= 0; i--) {
                if (insns[i].getOpcode() >= 0) return i;
            }
            return -1;
        }

        private AbstractInsnNode skipNonCode(AbstractInsnNode[] insns, int startIdx) {
            for (int i = startIdx; i < insns.length; i++) {
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

        // =====================================================================
        // Steps 2 & 3: Allocate column arrays and rewrite accesses
        // =====================================================================

        private void rewrite(SoACandidate candidate) {
            // Allocate new local slots for each column array
            Map<FieldAccess, Integer> columnLocals = new LinkedHashMap<>();
            for (FieldAccess fa : candidate.fields) {
                int local = maxLocals++;
                columnLocals.put(fa, local);
            }

            // Allocate locals for ColumnarView, fieldNames array, fieldDescs array
            int viewLocal = maxLocals++;
            int fieldNamesLocal = maxLocals++;
            int fieldDescsLocal = maxLocals++;

            // Build initialization code at method entry
            InsnList init = new InsnList();

            // Convert fields to ordered list for consistent indexing
            List<FieldAccess> fieldList = new ArrayList<>(candidate.fields);
            int fieldCount = fieldList.size();

            // Build String[] fieldNames as bytecode constants
            init.add(intConst(fieldCount));
            init.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            for (int i = 0; i < fieldCount; i++) {
                init.add(new InsnNode(Opcodes.DUP));
                init.add(intConst(i));
                init.add(new LdcInsnNode(fieldList.get(i).fieldName));
                init.add(new InsnNode(Opcodes.AASTORE));
            }
            init.add(new VarInsnNode(Opcodes.ASTORE, fieldNamesLocal));

            // Build String[] fieldDescs as bytecode constants
            init.add(intConst(fieldCount));
            init.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            for (int i = 0; i < fieldCount; i++) {
                init.add(new InsnNode(Opcodes.DUP));
                init.add(intConst(i));
                init.add(new LdcInsnNode(fieldList.get(i).fieldDesc));
                init.add(new InsnNode(Opcodes.AASTORE));
            }
            init.add(new VarInsnNode(Opcodes.ASTORE, fieldDescsLocal));

            // Call array.getColumnarView(elementClass, fieldNames, fieldDescs)
            init.add(new VarInsnNode(Opcodes.ALOAD, candidate.arrayLocal));
            init.add(new LdcInsnNode(candidate.elementClass));
            init.add(new VarInsnNode(Opcodes.ALOAD, fieldNamesLocal));
            init.add(new VarInsnNode(Opcodes.ALOAD, fieldDescsLocal));
            init.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ARRAY_INTERNAL,
                    "getColumnarView",
                    "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)"
                            + "Ldev/topo/Array$ColumnarView;",
                    false));
            init.add(new VarInsnNode(Opcodes.ASTORE, viewLocal));

            // For each field, call the typed column getter and store in column local
            for (int i = 0; i < fieldCount; i++) {
                FieldAccess fa = fieldList.get(i);
                int colLocal = columnLocals.get(fa);
                String getter = columnGetterMethod(fa.fieldDesc);
                String retType = columnReturnType(fa.fieldDesc);

                init.add(new VarInsnNode(Opcodes.ALOAD, viewLocal));
                init.add(intConst(i));
                init.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "dev/topo/Array$ColumnarView",
                        getter, "(I)" + retType, false));
                init.add(new VarInsnNode(Opcodes.ASTORE, colLocal));
            }

            // Insert init code before the method's first real instruction
            AbstractInsnNode insertPoint = instructions.getFirst();
            instructions.insert(insertPoint, init);

            // Step 3: Rewrite each access site
            for (AccessSite site : candidate.sites) {
                FieldAccess fa = new FieldAccess(site.fieldInsn.name, site.fieldInsn.desc);
                int colLocal = columnLocals.get(fa);

                // Find the ALOAD that loads the array ref (2 instructions before get)
                AbstractInsnNode aloadInsn = findAloadBefore(site.getInsn);

                // Build replacement: ALOAD col_field, ILOAD i, xALOAD
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ALOAD, colLocal));

                // Find the index load instruction (between ALOAD and INVOKEVIRTUAL)
                AbstractInsnNode indexInsn = findIndexLoadBetween(aloadInsn, site.getInsn);
                if (indexInsn != null) {
                    replacement.add(indexInsn.clone(null));
                }

                replacement.add(new InsnNode(primitiveArrayLoad(fa.fieldDesc)));

                // Remove original sequence: ALOAD, ILOAD, INVOKEVIRTUAL, CHECKCAST, GETFIELD
                instructions.insert(site.fieldInsn, replacement);

                if (aloadInsn != null) instructions.remove(aloadInsn);
                if (indexInsn != null) instructions.remove(indexInsn);
                instructions.remove(site.getInsn);
                instructions.remove(site.castInsn);
                instructions.remove(site.fieldInsn);
            }
        }

        private AbstractInsnNode findAloadBefore(MethodInsnNode getInsn) {
            AbstractInsnNode prev = getInsn.getPrevious();
            while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
            if (prev == null) return null;
            // prev should be the index load, go one more back for ALOAD
            AbstractInsnNode prev2 = prev.getPrevious();
            while (prev2 != null && prev2.getOpcode() < 0) prev2 = prev2.getPrevious();
            if (prev2 instanceof VarInsnNode vi && vi.getOpcode() == Opcodes.ALOAD) {
                return prev2;
            }
            return null;
        }

        private AbstractInsnNode findIndexLoadBetween(AbstractInsnNode aload, MethodInsnNode getInsn) {
            if (aload == null) return null;
            AbstractInsnNode next = aload.getNext();
            while (next != null && next != getInsn) {
                if (next.getOpcode() >= 0) return next;
                next = next.getNext();
            }
            return null;
        }

        // =====================================================================
        // Utility
        // =====================================================================

        private static boolean isPrimitiveDescriptor(String desc) {
            return switch (desc) {
                case "I", "J", "F", "D", "B", "S", "C", "Z" -> true;
                default -> false;
            };
        }

        private static AbstractInsnNode intConst(int value) {
            if (value >= 0 && value <= 5) {
                return new InsnNode(Opcodes.ICONST_0 + value);
            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                return new IntInsnNode(Opcodes.BIPUSH, value);
            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                return new IntInsnNode(Opcodes.SIPUSH, value);
            } else {
                return new LdcInsnNode(value);
            }
        }

        private static String columnGetterMethod(String desc) {
            return switch (desc) {
                case "F" -> "getFloatColumn";
                case "D" -> "getDoubleColumn";
                case "I" -> "getIntColumn";
                case "J" -> "getLongColumn";
                default -> throw new IllegalArgumentException(
                        "No column getter for descriptor: " + desc);
            };
        }

        private static String columnReturnType(String desc) {
            return switch (desc) {
                case "F" -> "[F";
                case "D" -> "[D";
                case "I" -> "[I";
                case "J" -> "[J";
                case "B", "Z" -> "[B";
                case "S" -> "[S";
                case "C" -> "[C";
                default -> throw new IllegalArgumentException(
                        "No column return type for descriptor: " + desc);
            };
        }

        private static int primitiveArrayLoad(String desc) {
            return switch (desc) {
                case "I" -> Opcodes.IALOAD;
                case "J" -> Opcodes.LALOAD;
                case "F" -> Opcodes.FALOAD;
                case "D" -> Opcodes.DALOAD;
                case "B", "Z" -> Opcodes.BALOAD;
                case "S" -> Opcodes.SALOAD;
                case "C" -> Opcodes.CALOAD;
                default -> throw new IllegalArgumentException("Not a primitive: " + desc);
            };
        }

    }
}
