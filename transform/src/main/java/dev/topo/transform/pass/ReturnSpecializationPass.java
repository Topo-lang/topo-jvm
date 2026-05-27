package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Eliminates dead PUTFIELD instructions for unused return fields in multi-return methods.
 *
 * <p>When a method returns an object with multiple fields (multi-return pattern),
 * but callers only use a subset of those fields, the PUTFIELD instructions for
 * dead fields are replaced with POP instructions to avoid unnecessary writes.</p>
 *
 * <p>Dead fields are determined by analyzing metadata: a field is dead if it appears
 * in returnParams but is absent from all callSites' usedReturns and all logicBlocks'
 * pipelineAnalysis demand lists.</p>
 */
// @category: COVERED
public class ReturnSpecializationPass implements BasePass {
    private final JsonObject config;
    private final JsonObject metadata;
    /** Maps qualified method name -> set of dead field names. */
    private final Map<String, Set<String>> deadFieldsByMethod;
    /** host_method qualified name → ordered list of fields
     *  this Pass actually eliminated. Populated only when the visitor sees
     *  a method whose deadFieldsByMethod entry was non-empty AND at least
     *  one PUTFIELD was rewritten to POP. Matches LLVM-side schema
     *  (`entries:[{host_method, eliminated_field_indices}]`) modulo the
     *  field-name vs field-index difference — JVM doesn't number struct
     *  fields by index, so we emit the names users wrote in `.topo`. */
    private final Map<String, Set<String>> eliminatedByMethod = new LinkedHashMap<>();

    public ReturnSpecializationPass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.deadFieldsByMethod = computeDeadFields();
    }

    /** Per-method eliminations applied across all classes visited.
     *  PassPipeline aggregates these into the sidecar's `entries` array. */
    public Map<String, Set<String>> getEliminatedByMethod() {
        return eliminatedByMethod;
    }

    private Map<String, Set<String>> computeDeadFields() {
        Map<String, Set<String>> result = new HashMap<>();
        if (!metadata.has("functions")) return result;

        var functions = metadata.getAsJsonObject("functions");

        // Step 1: Collect multi-return methods and their return param names.
        // Also pre-seed the live set from any `with returns(...)` ceiling the
        // function declared — this ceiling alone makes the unnamed (dead)
        // positions eliminable regardless of call-site info.
        Map<String, Set<String>> allReturnParams = new HashMap<>();
        Map<String, Set<String>> declaredCeiling = new HashMap<>();
        Set<String> hasCeiling = new HashSet<>();
        for (var entry : functions.entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            if (!fn.has("isMultiReturn") || !fn.get("isMultiReturn").getAsBoolean()) continue;
            if (!fn.has("returnParams")) continue;

            String qn = fn.get("qualifiedName").getAsString();
            Set<String> paramNames = new HashSet<>();
            for (var rp : fn.getAsJsonArray("returnParams")) {
                paramNames.add(rp.getAsJsonObject().get("name").getAsString());
            }
            allReturnParams.put(qn, paramNames);

            if (fn.has("hasUsedReturnsClause") && fn.get("hasUsedReturnsClause").getAsBoolean()) {
                hasCeiling.add(qn);
                Set<String> ceiling = new HashSet<>();
                if (fn.has("usedReturns")) {
                    for (var n : fn.getAsJsonArray("usedReturns")) {
                        ceiling.add(n.getAsString());
                    }
                }
                declaredCeiling.put(qn, ceiling);
            }
        }

        if (allReturnParams.isEmpty()) return result;

        // Step 2: Collect live fields from callSites
        Map<String, Set<String>> liveFields = new HashMap<>();
        Map<String, Boolean> hasCallSite = new HashMap<>();
        Map<String, Boolean> hasFullStyle = new HashMap<>();

        if (metadata.has("callSites")) {
            for (var cs : metadata.getAsJsonArray("callSites")) {
                var site = cs.getAsJsonObject();
                String callee = site.get("callee").getAsString();
                if (!allReturnParams.containsKey(callee)) continue;

                hasCallSite.put(callee, true);

                // "full" style means all fields are live
                if (site.has("style") && "full".equals(site.get("style").getAsString())) {
                    hasFullStyle.put(callee, true);
                    continue;
                }

                if (site.has("usedReturns")) {
                    Set<String> live = liveFields.computeIfAbsent(callee, k -> new HashSet<>());
                    for (var ur : site.getAsJsonArray("usedReturns")) {
                        live.add(ur.getAsString());
                    }
                }
            }
        }

        // Step 3: Collect demand fields from logicBlocks pipelineAnalysis
        if (metadata.has("logicBlocks")) {
            for (var entry : metadata.getAsJsonObject("logicBlocks").entrySet()) {
                var block = entry.getValue().getAsJsonObject();
                if (!block.has("pipelineAnalysis")) continue;
                var pa = block.getAsJsonObject("pipelineAnalysis");
                if (!pa.has("demand")) continue;

                for (var d : pa.getAsJsonArray("demand")) {
                    String fieldName = d.getAsString();
                    for (String qn : allReturnParams.keySet()) {
                        if (allReturnParams.get(qn).contains(fieldName)) {
                            liveFields.computeIfAbsent(qn, k -> new HashSet<>()).add(fieldName);
                        }
                    }
                }
            }
        }

        // Step 4: Compute dead fields = allReturnParams - liveFields.
        // A declared `with returns(...)` ceiling overrides the
        // "no-callsite means all live" and "any Full means all live"
        // defaults — the callee has explicitly authorized elision.
        for (var entry : allReturnParams.entrySet()) {
            String qn = entry.getKey();
            Set<String> allParams = entry.getValue();

            boolean hasDeclaredCeiling = hasCeiling.contains(qn);

            // Conservative: no callSites AND no ceiling means all fields stay live.
            if (!hasCallSite.containsKey(qn) && !hasDeclaredCeiling) continue;

            // Any "full" style callSite AND no ceiling means all fields stay live.
            // When the callee has a ceiling, Full is still clamped by it.
            if (hasFullStyle.containsKey(qn) && !hasDeclaredCeiling) continue;

            Set<String> live = new HashSet<>(liveFields.getOrDefault(qn, Collections.emptySet()));
            if (hasDeclaredCeiling) {
                // Live fields are clamped to the declared ceiling.  If no call
                // site contributed any names, the ceiling itself is the live set.
                Set<String> ceiling = declaredCeiling.getOrDefault(qn, Collections.emptySet());
                if (live.isEmpty()) {
                    live.addAll(ceiling);
                } else {
                    live.retainAll(ceiling);
                }
            }

            Set<String> dead = new HashSet<>(allParams);
            dead.removeAll(live);

            if (!dead.isEmpty()) {
                result.put(qn, dead);
            }
        }

        return result;
    }

    @Override
    public ClassVisitor createVisitor(ClassWriter writer) {
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
                Set<String> deadFields = QualifiedNameMatch.get(deadFieldsByMethod, className, name);
                if (deadFields != null && !deadFields.isEmpty()) {
                    String hostMethod = className.replace("/", "::") + "::" + name;
                    return new ReturnSpecMethodNode(Opcodes.ASM9, access, name, descriptor,
                            signature, exceptions != null ? exceptions : new String[0],
                            downstream, deadFields, hostMethod);
                }
                return downstream;
            }
        };
    }

    /**
     * MethodNode that replaces PUTFIELD instructions for dead fields with POP instructions.
     *
     * <p>Stack before PUTFIELD: ..., objectref, value</p>
     * <ul>
     *   <li>Category-1 value (int, float, ref): POP + POP (pop value, pop objectref)</li>
     *   <li>Category-2 value (long, double): POP2 + POP (pop value, pop objectref)</li>
     * </ul>
     */
    private class ReturnSpecMethodNode extends MethodNode {
        private final MethodVisitor downstream;
        private final Set<String> deadFields;
        private final String hostMethod;

        ReturnSpecMethodNode(int api, int access, String name, String descriptor,
                            String signature, String[] exceptions,
                            MethodVisitor downstream, Set<String> deadFields,
                            String hostMethod) {
            super(api, access, name, descriptor, signature, exceptions);
            this.downstream = downstream;
            this.deadFields = deadFields;
            this.hostMethod = hostMethod;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            eliminateDeadFields();
            accept(downstream);
        }

        private void eliminateDeadFields() {
            // Record only fields actually rewritten — the dead set is the
            // *declarable* dead set; until we see a real PUTFIELD on this
            // method body, no transformation occurred.
            Set<String> rewritten = null;
            for (AbstractInsnNode insn : instructions.toArray()) {
                if (insn instanceof FieldInsnNode field) {
                    if (field.getOpcode() == Opcodes.PUTFIELD && deadFields.contains(field.name)) {
                        InsnList replacement = new InsnList();
                        if (isCategory2(field.desc)) {
                            replacement.add(new InsnNode(Opcodes.POP2)); // pop category-2 value
                        } else {
                            replacement.add(new InsnNode(Opcodes.POP));  // pop category-1 value
                        }
                        replacement.add(new InsnNode(Opcodes.POP)); // pop objectref
                        instructions.insert(insn, replacement);
                        instructions.remove(insn);
                        if (rewritten == null) rewritten = new LinkedHashSet<>();
                        rewritten.add(field.name);
                    }
                }
            }
            if (rewritten != null && !rewritten.isEmpty()) {
                eliminatedByMethod.merge(hostMethod, rewritten, (a, b) -> { a.addAll(b); return a; });
            }
        }

        private boolean isCategory2(String desc) {
            return "J".equals(desc) || "D".equals(desc);
        }
    }
}
