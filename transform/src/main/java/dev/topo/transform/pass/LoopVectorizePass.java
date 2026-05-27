package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Generates {@code jdk.incubator.vector} (Vector API) bytecode for simple
 * counted loops in parallel-stage functions.
 *
 * <p>v1 scope: {@code float[]} arrays with simple arithmetic
 * ({@code FALOAD}/{@code FASTORE}/{@code FADD}/{@code FMUL}/{@code FSUB}/{@code FDIV}).
 * Handles two patterns:</p>
 * <ul>
 *   <li>{@code a[i] = b[i] <op> c[i]} — binary op between two arrays, store to third</li>
 *   <li>{@code sum += a[i]} — simple reduction</li>
 * </ul>
 *
 * <p>Uses the Tree API (MethodNode) for full method body analysis and rewriting.</p>
 */
// @category: COVERED
// Demoted OPT → COVERED.
// Rationale: HotSpot C2's auto-vectorizer already covers the streaming
// `scaleAndAdd` / `reduceSum` patterns; only the gathered-access case
// (`data[indices[i]]`) is outside C2 and the aggregate friendly
// forced/vanilla ≈ 0.905 is too marginal to carry the OPT `≤ 0.90` bar.
// The pass also has method-body pattern detection (`detectLoop` /
// `analyzeBody`), which counts as an internal cost heuristic.
public class LoopVectorizePass implements BasePass {
    private final JsonObject config;
    private final JsonObject metadata;
    private final Set<String> parallelStageFunctions;

    public LoopVectorizePass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.parallelStageFunctions = buildParallelStageFunctions();
    }

    /**
     * Collects ALL function names that appear in ANY stage with 2+ functions
     * (parallel-stage functions), plus the orchestrator itself.
     */
    private Set<String> buildParallelStageFunctions() {
        Set<String> result = new HashSet<>();
        if (!metadata.has("logicBlocks")) return result;
        for (var entry : metadata.getAsJsonObject("logicBlocks").entrySet()) {
            var block = entry.getValue().getAsJsonObject();
            if (!block.has("calledFunctions") || !block.has("stages")) continue;
            var calledFunctions = block.getAsJsonArray("calledFunctions");
            var stages = block.getAsJsonArray("stages");

            // Group functions by stage
            Map<Integer, List<String>> stageGroups = new HashMap<>();
            for (int i = 0; i < calledFunctions.size() && i < stages.size(); i++) {
                int stage = stages.get(i).getAsInt();
                stageGroups.computeIfAbsent(stage, k -> new ArrayList<>())
                    .add(calledFunctions.get(i).getAsString());
            }

            // Functions in stages with 2+ entries are parallel-stage functions
            for (var group : stageGroups.values()) {
                if (group.size() >= 2) {
                    result.addAll(group);
                }
            }
            // Also include the orchestrator
            if (block.has("qualifiedName")) {
                result.add(block.get("qualifiedName").getAsString());
            }
        }
        return result;
    }

    private boolean shouldVectorize(String className, String methodName) {
        String qualifiedName = className.replace("/", "::") + "::" + methodName;
        if (parallelStageFunctions.contains(qualifiedName)) return true;
        // Namespace-level match
        int lastSlash = className.lastIndexOf('/');
        if (lastSlash >= 0) {
            String namespace = className.substring(0, lastSlash).replace("/", "::");
            if (parallelStageFunctions.contains(namespace + "::" + methodName)) return true;
        }
        return false;
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
                if (shouldVectorize(className, name)) {
                    return new VectorizingMethodNode(Opcodes.ASM9, access, name, descriptor,
                            signature, exceptions != null ? exceptions : new String[0],
                            downstream);
                }
                return downstream;
            }
        };
    }

    // ========================================================================
    // Loop detection and analysis
    // ========================================================================

    /** Information about a detected counted for-loop. */
    private static class LoopInfo {
        int inductionVar;
        AbstractInsnNode boundInsn;   // instruction that loads the bound
        LabelNode headLabel;
        LabelNode exitLabel;
        int bodyStartIdx;             // index of first body instruction
        int bodyEndIdx;               // index of IINC instruction
    }

    /** Analysis result for the loop body. */
    private static class BodyAnalysis {
        boolean isVectorizable;
        /** Array local variable indices for loads. */
        List<Integer> sourceArrayLocals = new ArrayList<>();
        /** Array local variable index for stores. */
        int destArrayLocal = -1;
        /** Arithmetic opcodes found in body. */
        List<Integer> arithmeticOps = new ArrayList<>();
        /** Whether a reduction pattern was detected. */
        boolean hasReduction;
        /** Local variable used as reduction accumulator. */
        int reductionVar = -1;
        /** Whether a gathered access pattern (data[indices[i]]) was detected. */
        boolean hasGatheredAccess;
        /** Local variable holding the data array for gathered access. */
        int gatheredDataLocal = -1;
        /** Local variable holding the index array for gathered access. */
        int gatheredIndexLocal = -1;
    }

    /**
     * MethodNode that performs loop vectorization in {@code visitEnd()}.
     */
    private static class VectorizingMethodNode extends MethodNode {
        private final MethodVisitor downstream;

        private static final String FLOAT_VECTOR = "jdk/incubator/vector/FloatVector";
        private static final String VECTOR_SPECIES = "jdk/incubator/vector/VectorSpecies";
        private static final String VECTOR_OPERATORS = "jdk/incubator/vector/VectorOperators";
        private static final String VECTOR_BASE = "jdk/incubator/vector/Vector";

        VectorizingMethodNode(int api, int access, String name, String descriptor,
                             String signature, String[] exceptions,
                             MethodVisitor downstream) {
            super(api, access, name, descriptor, signature, exceptions);
            this.downstream = downstream;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            vectorizeLoops();
            accept(downstream);
        }

        private void vectorizeLoops() {
            // Scan for loop patterns and transform the first eligible one.
            // Processing multiple loops in a single method is left for future versions.
            LoopInfo loop = detectLoop();
            if (loop == null) return;

            BodyAnalysis body = analyzeBody(loop);
            if (!body.isVectorizable) return;

            if (body.hasGatheredAccess) {
                rewriteGatheredLoop(loop, body);
            } else if (body.hasReduction) {
                rewriteReductionLoop(loop, body);
            } else {
                rewriteArrayOpLoop(loop, body);
            }
        }

        /**
         * Scans instructions for a counted for-loop pattern:
         * <pre>
         *   ICONST_0 / BIPUSH / SIPUSH / LDC  0
         *   ISTORE inductionVar
         *   LABEL loopHead
         *   ILOAD inductionVar
         *   ILOAD / GETFIELD bound
         *   IF_ICMPGE exitLabel
         *     ... loop body ...
         *   IINC inductionVar 1
         *   GOTO loopHead
         *   LABEL exit
         * </pre>
         */
        private LoopInfo detectLoop() {
            AbstractInsnNode[] insns = instructions.toArray();
            if (insns.length < 8) return null;

            for (int i = 0; i < insns.length - 6; i++) {
                // Look for: const 0 → ISTORE → LABEL → ILOAD → load bound → IF_ICMPGE
                if (!isIntConst0(insns[i])) continue;

                AbstractInsnNode storeInsn = nextReal(insns, i + 1);
                if (storeInsn == null || storeInsn.getOpcode() != Opcodes.ISTORE) continue;
                int inductionVar = ((VarInsnNode) storeInsn).var;

                // Next should be a label (loop head)
                AbstractInsnNode labelInsn = nextInsn(insns, indexOf(storeInsn) + 1);
                if (labelInsn == null || !(labelInsn instanceof LabelNode headLabel)) continue;

                // After label: ILOAD inductionVar
                AbstractInsnNode loadInduction = nextReal(insns, indexOf(labelInsn) + 1);
                if (loadInduction == null || loadInduction.getOpcode() != Opcodes.ILOAD) continue;
                if (((VarInsnNode) loadInduction).var != inductionVar) continue;

                // Next: load bound (ILOAD or GETFIELD)
                AbstractInsnNode boundInsn = nextReal(insns, indexOf(loadInduction) + 1);
                if (boundInsn == null) continue;
                if (boundInsn.getOpcode() != Opcodes.ILOAD
                    && boundInsn.getOpcode() != Opcodes.GETFIELD) continue;

                // Next: IF_ICMPGE exitLabel
                AbstractInsnNode cmpInsn = nextReal(insns, indexOf(boundInsn) + 1);
                if (cmpInsn == null || cmpInsn.getOpcode() != Opcodes.IF_ICMPGE) continue;
                LabelNode exitLabel = ((JumpInsnNode) cmpInsn).label;

                // Find IINC inductionVar 1 and GOTO headLabel before exitLabel
                int bodyStart = indexOf(cmpInsn) + 1;
                int iincIdx = -1;
                int gotoIdx = -1;

                for (int j = bodyStart; j < insns.length; j++) {
                    if (insns[j] instanceof LabelNode ln && ln == exitLabel) break;
                    if (insns[j] instanceof IincInsnNode iinc) {
                        if (iinc.var == inductionVar && iinc.incr == 1) {
                            iincIdx = j;
                        }
                    }
                    if (insns[j].getOpcode() == Opcodes.GOTO) {
                        JumpInsnNode gotoInsn = (JumpInsnNode) insns[j];
                        if (gotoInsn.label == headLabel) {
                            gotoIdx = j;
                        }
                    }
                }

                if (iincIdx < 0 || gotoIdx < 0) continue;
                if (gotoIdx != iincIdx + 1 && gotoIdx != nextRealIndex(insns, iincIdx + 1))
                    continue;

                LoopInfo info = new LoopInfo();
                info.inductionVar = inductionVar;
                info.boundInsn = boundInsn;
                info.headLabel = headLabel;
                info.exitLabel = exitLabel;
                info.bodyStartIdx = bodyStart;
                info.bodyEndIdx = iincIdx;
                return info;
            }
            return null;
        }

        /**
         * Analyzes the loop body for vectorizability.
         * v1: only float[] (FALOAD/FASTORE) with simple arithmetic.
         */
        private BodyAnalysis analyzeBody(LoopInfo loop) {
            AbstractInsnNode[] insns = instructions.toArray();
            BodyAnalysis result = new BodyAnalysis();
            result.isVectorizable = false;

            boolean hasFloatArrayLoad = false;
            boolean hasFloatArrayStore = false;
            Set<Integer> arrayLoadLocals = new LinkedHashSet<>();
            int storeArrayLocal = -1;

            // Check for reduction pattern: FLOAD accum / FALOAD / FADD / FSTORE accum
            int reductionVar = -1;

            for (int j = loop.bodyStartIdx; j < loop.bodyEndIdx; j++) {
                AbstractInsnNode insn = insns[j];
                int op = insn.getOpcode();

                // Skip labels, frames, line numbers
                if (op == -1) continue;

                // Disqualifiers: method calls, conditional branches
                if (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode) {
                    return result;
                }
                if (op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE) return result;
                if (op == Opcodes.GOTO) {
                    // GOTO is acceptable only if it's the loop back-edge
                    if (insn instanceof JumpInsnNode ji && ji.label == loop.headLabel) continue;
                    return result;
                }
                if (op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH) return result;

                // Non-float array access → disqualify (IALOAD allowed for gathered pattern)
                if (op == Opcodes.IASTORE
                    || op == Opcodes.DALOAD || op == Opcodes.DASTORE
                    || op == Opcodes.LALOAD || op == Opcodes.LASTORE
                    || op == Opcodes.AALOAD || op == Opcodes.AASTORE
                    || op == Opcodes.BALOAD || op == Opcodes.BASTORE
                    || op == Opcodes.CALOAD || op == Opcodes.CASTORE
                    || op == Opcodes.SALOAD || op == Opcodes.SASTORE) {
                    return result;
                }

                if (op == Opcodes.FALOAD) {
                    // Check for gathered access: data[indices[i]]
                    // Bytecode: ALOAD data, ALOAD indices, ILOAD i, IALOAD, FALOAD
                    int gatheredStart = findGatheredPattern(insns, j, loop.inductionVar);
                    if (gatheredStart >= 0) {
                        hasFloatArrayLoad = true;
                        result.hasGatheredAccess = true;
                        result.gatheredDataLocal = ((VarInsnNode) insns[gatheredStart]).var;
                        // The index array is one real instruction after the data ALOAD
                        int idxInsn = nextRealIndex(insns, gatheredStart + 1);
                        result.gatheredIndexLocal = ((VarInsnNode) insns[idxInsn]).var;
                        continue;
                    }

                    hasFloatArrayLoad = true;
                    // Track the array local: look back for ALOAD before the index ILOAD
                    int arrayLocal = findArrayLocalBefore(insns, j, loop.inductionVar);
                    if (arrayLocal >= 0) {
                        arrayLoadLocals.add(arrayLocal);
                    } else {
                        return result; // cannot determine array local
                    }
                }

                // Allow IALOAD only as part of gathered pattern (already handled above)
                if (op == Opcodes.IALOAD) {
                    // If we reach here, IALOAD was not part of a gathered pattern
                    // detected by the FALOAD handler — this is a standalone int array
                    // load which we cannot vectorize
                    if (!result.hasGatheredAccess) return result;
                }

                if (op == Opcodes.FASTORE) {
                    hasFloatArrayStore = true;
                    // The array ref for FASTORE: look back to find ALOAD before index
                    storeArrayLocal = findStoreArrayLocal(insns, j, loop);
                    if (storeArrayLocal < 0) return result;
                }

                // Track arithmetic
                if (op == Opcodes.FADD || op == Opcodes.FSUB
                    || op == Opcodes.FMUL || op == Opcodes.FDIV) {
                    result.arithmeticOps.add(op);
                }

                // Detect reduction: FLOAD var ... FADD ... FSTORE var
                if (op == Opcodes.FSTORE && insn instanceof VarInsnNode vin) {
                    if (vin.var != loop.inductionVar) {
                        // Check if same var was loaded earlier in the body
                        for (int k = loop.bodyStartIdx; k < j; k++) {
                            if (insns[k].getOpcode() == Opcodes.FLOAD
                                && insns[k] instanceof VarInsnNode fload
                                && fload.var == vin.var) {
                                reductionVar = vin.var;
                                break;
                            }
                        }
                    }
                }
            }

            if (!hasFloatArrayLoad) return result;
            if (result.arithmeticOps.isEmpty()) return result;

            // Gathered access reduction: data[indices[i]] with accumulator
            if (result.hasGatheredAccess && reductionVar >= 0) {
                result.isVectorizable = true;
                result.hasReduction = true;
                result.reductionVar = reductionVar;
                return result;
            }

            // Determine pattern
            if (reductionVar >= 0 && !hasFloatArrayStore) {
                // Reduction pattern: sum += a[i]
                result.isVectorizable = true;
                result.hasReduction = true;
                result.reductionVar = reductionVar;
                result.sourceArrayLocals.addAll(arrayLoadLocals);
            } else if (hasFloatArrayStore && arrayLoadLocals.size() >= 1) {
                // Array op pattern: a[i] = b[i] <op> c[i]  or  a[i] = b[i] <op> scalar
                result.isVectorizable = true;
                result.hasReduction = false;
                result.sourceArrayLocals.addAll(arrayLoadLocals);
                result.destArrayLocal = storeArrayLocal;
            }

            return result;
        }

        /**
         * Finds the array local variable for an xALOAD instruction by looking
         * backward for the ALOAD that precedes the index load (ILOAD inductionVar).
         */
        private int findArrayLocalBefore(AbstractInsnNode[] insns, int faloadIdx,
                                          int inductionVar) {
            // Pattern: ALOAD arrayLocal, ILOAD inductionVar, FALOAD
            for (int k = faloadIdx - 1; k >= 0; k--) {
                if (insns[k].getOpcode() == Opcodes.ILOAD
                    && insns[k] instanceof VarInsnNode vin
                    && vin.var == inductionVar) {
                    // The ALOAD should be right before this ILOAD
                    for (int m = k - 1; m >= 0; m--) {
                        if (insns[m].getOpcode() == -1) continue; // skip pseudo
                        if (insns[m].getOpcode() == Opcodes.ALOAD
                            && insns[m] instanceof VarInsnNode aload) {
                            return aload.var;
                        }
                        break;
                    }
                    break;
                }
            }
            return -1;
        }

        /**
         * Finds the destination array local for FASTORE. The pattern is:
         * ALOAD destArray, ILOAD inductionVar, ..., FASTORE
         */
        private int findStoreArrayLocal(AbstractInsnNode[] insns, int fastoreIdx,
                                         LoopInfo loop) {
            // Walk backward through the body to find the ALOAD, ILOAD pair
            // that sets up the array store target
            for (int k = fastoreIdx - 1; k >= loop.bodyStartIdx; k--) {
                if (insns[k].getOpcode() == Opcodes.ILOAD
                    && insns[k] instanceof VarInsnNode vin
                    && vin.var == loop.inductionVar) {
                    // The ALOAD should be right before
                    for (int m = k - 1; m >= loop.bodyStartIdx; m--) {
                        if (insns[m].getOpcode() == -1) continue;
                        if (insns[m].getOpcode() == Opcodes.ALOAD
                            && insns[m] instanceof VarInsnNode aload) {
                            return aload.var;
                        }
                        break;
                    }
                }
            }
            return -1;
        }

        /**
         * Detects the gathered access pattern ending at FALOAD:
         * {@code ALOAD data, ALOAD indices, ILOAD i, IALOAD, FALOAD}
         * Returns the index of the data ALOAD or -1 if not found.
         */
        private static int findGatheredPattern(AbstractInsnNode[] insns, int faloadIdx,
                                                int inductionVar) {
            // Walk backward: FALOAD ← IALOAD ← ILOAD i ← ALOAD indices ← ALOAD data
            int ialoadIdx = findPrevReal(insns, faloadIdx);
            if (ialoadIdx < 0 || insns[ialoadIdx].getOpcode() != Opcodes.IALOAD) return -1;

            int iloadIdx = findPrevReal(insns, ialoadIdx);
            if (iloadIdx < 0 || insns[iloadIdx].getOpcode() != Opcodes.ILOAD) return -1;
            if (((VarInsnNode) insns[iloadIdx]).var != inductionVar) return -1;

            int indicesIdx = findPrevReal(insns, iloadIdx);
            if (indicesIdx < 0 || insns[indicesIdx].getOpcode() != Opcodes.ALOAD) return -1;

            int dataIdx = findPrevReal(insns, indicesIdx);
            if (dataIdx < 0 || insns[dataIdx].getOpcode() != Opcodes.ALOAD) return -1;

            return dataIdx;
        }

        /** Finds the previous instruction with opcode >= 0 (skipping pseudo-instructions). */
        private static int findPrevReal(AbstractInsnNode[] insns, int from) {
            for (int i = from - 1; i >= 0; i--) {
                if (insns[i].getOpcode() >= 0) return i;
            }
            return -1;
        }

        // ====================================================================
        // Code generation
        // ====================================================================

        /**
         * Rewrites an array-operation loop:
         * {@code a[i] = b[i] <op> c[i]} → Vector API.
         */
        private void rewriteArrayOpLoop(LoopInfo loop, BodyAnalysis body) {
            AbstractInsnNode[] insns = instructions.toArray();

            // Allocate local variables for species, vectorBound, vector temps
            int speciesLocal = maxLocals++;
            int boundLocal = maxLocals++;
            int nextVecLocal = maxLocals;

            // We need one vector local per source array + one for the result
            int[] vecLocals = new int[body.sourceArrayLocals.size()];
            for (int i = 0; i < vecLocals.length; i++) {
                vecLocals[i] = maxLocals++;
            }
            int resultVecLocal = maxLocals++;

            // Build the replacement instruction list
            InsnList replacement = new InsnList();

            // --- SPECIES = FloatVector.SPECIES_PREFERRED ---
            replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, FLOAT_VECTOR,
                    "SPECIES_PREFERRED", "L" + VECTOR_SPECIES + ";"));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, speciesLocal));

            // --- int bound = SPECIES.loopBound(n) ---
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(cloneBoundLoad(loop.boundInsn));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, VECTOR_SPECIES,
                    "loopBound", "(I)I", true));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, boundLocal));

            // --- Vector loop: for (i = 0; i < bound; i += SPECIES.length()) ---
            LabelNode vectorHead = new LabelNode();
            LabelNode vectorExit = new LabelNode();

            replacement.add(new InsnNode(Opcodes.ICONST_0));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, loop.inductionVar));

            replacement.add(vectorHead);
            replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
            replacement.add(new VarInsnNode(Opcodes.ILOAD, boundLocal));
            replacement.add(new JumpInsnNode(Opcodes.IF_ICMPGE, vectorExit));

            // Load vectors from source arrays
            for (int s = 0; s < body.sourceArrayLocals.size(); s++) {
                int arrayLocal = body.sourceArrayLocals.get(s);
                replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
                replacement.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
                replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FLOAT_VECTOR,
                        "fromArray",
                        "(L" + VECTOR_SPECIES + ";[FI)L" + FLOAT_VECTOR + ";",
                        false));
                replacement.add(new VarInsnNode(Opcodes.ASTORE, vecLocals[s]));
            }

            // Apply arithmetic: vec0 <op> vec1 → result
            if (body.sourceArrayLocals.size() >= 2 && !body.arithmeticOps.isEmpty()) {
                replacement.add(new VarInsnNode(Opcodes.ALOAD, vecLocals[0]));
                replacement.add(new VarInsnNode(Opcodes.ALOAD, vecLocals[1]));
                String vecMethod = vectorMethodName(body.arithmeticOps.get(0));
                replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FLOAT_VECTOR,
                        vecMethod,
                        "(L" + VECTOR_BASE + ";)L" + FLOAT_VECTOR + ";",
                        false));
                replacement.add(new VarInsnNode(Opcodes.ASTORE, resultVecLocal));
            } else if (body.sourceArrayLocals.size() == 1) {
                // Single array with scalar op — store as-is for now
                replacement.add(new VarInsnNode(Opcodes.ALOAD, vecLocals[0]));
                replacement.add(new VarInsnNode(Opcodes.ASTORE, resultVecLocal));
            }

            // Store result: result.intoArray(destArray, i)
            replacement.add(new VarInsnNode(Opcodes.ALOAD, resultVecLocal));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, body.destArrayLocal));
            replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FLOAT_VECTOR,
                    "intoArray", "([FI)V", false));

            // Increment: i += SPECIES.length()
            replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, VECTOR_SPECIES,
                    "length", "()I", true));
            replacement.add(new InsnNode(Opcodes.IADD));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, loop.inductionVar));
            replacement.add(new JumpInsnNode(Opcodes.GOTO, vectorHead));

            replacement.add(vectorExit);

            // --- Scalar tail: set i = bound, then original loop body runs from bound to n ---
            replacement.add(new VarInsnNode(Opcodes.ILOAD, boundLocal));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, loop.inductionVar));

            // The original loop (head label onwards) remains for the scalar tail.
            // Insert the vector preamble before the original init (ICONST_0, ISTORE).
            // We replace the ICONST_0 + ISTORE pair with our vectorized code + tail setup.

            // Find the original ICONST_0 and ISTORE that initialize the induction var
            AbstractInsnNode initConst = findInitConst(insns, loop);
            AbstractInsnNode initStore = nextReal(insns, indexOf(initConst) + 1);

            // Insert vector preamble before the original init
            instructions.insertBefore(initConst, replacement);

            // Remove the original ICONST_0 and ISTORE (they're replaced by tail setup)
            instructions.remove(initConst);
            instructions.remove(initStore);
        }

        /**
         * Rewrites a reduction loop: {@code sum += a[i]} → Vector API with reduceLanes.
         */
        private void rewriteReductionLoop(LoopInfo loop, BodyAnalysis body) {
            AbstractInsnNode[] insns = instructions.toArray();

            int speciesLocal = maxLocals++;
            int boundLocal = maxLocals++;
            int accumVecLocal = maxLocals++;
            int tempVecLocal = maxLocals++;

            InsnList replacement = new InsnList();

            // --- SPECIES ---
            replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, FLOAT_VECTOR,
                    "SPECIES_PREFERRED", "L" + VECTOR_SPECIES + ";"));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, speciesLocal));

            // --- bound = SPECIES.loopBound(n) ---
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(cloneBoundLoad(loop.boundInsn));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, VECTOR_SPECIES,
                    "loopBound", "(I)I", true));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, boundLocal));

            // --- accumVec = FloatVector.zero(SPECIES) ---
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FLOAT_VECTOR,
                    "zero",
                    "(L" + VECTOR_SPECIES + ";)L" + FLOAT_VECTOR + ";",
                    false));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, accumVecLocal));

            // --- Vector loop ---
            LabelNode vectorHead = new LabelNode();
            LabelNode vectorExit = new LabelNode();

            replacement.add(new InsnNode(Opcodes.ICONST_0));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, loop.inductionVar));

            replacement.add(vectorHead);
            replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
            replacement.add(new VarInsnNode(Opcodes.ILOAD, boundLocal));
            replacement.add(new JumpInsnNode(Opcodes.IF_ICMPGE, vectorExit));

            // Load from source array
            int srcArray = body.sourceArrayLocals.get(0);
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, srcArray));
            replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FLOAT_VECTOR,
                    "fromArray",
                    "(L" + VECTOR_SPECIES + ";[FI)L" + FLOAT_VECTOR + ";",
                    false));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, tempVecLocal));

            // accumVec = accumVec.add(tempVec)
            String vecOp = vectorMethodName(body.arithmeticOps.get(0));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, accumVecLocal));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, tempVecLocal));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FLOAT_VECTOR,
                    vecOp,
                    "(L" + VECTOR_BASE + ";)L" + FLOAT_VECTOR + ";",
                    false));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, accumVecLocal));

            // i += SPECIES.length()
            replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, VECTOR_SPECIES,
                    "length", "()I", true));
            replacement.add(new InsnNode(Opcodes.IADD));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, loop.inductionVar));
            replacement.add(new JumpInsnNode(Opcodes.GOTO, vectorHead));

            replacement.add(vectorExit);

            // --- Reduce: sum += accumVec.reduceLanes(VectorOperators.ADD) ---
            replacement.add(new VarInsnNode(Opcodes.FLOAD, body.reductionVar));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, accumVecLocal));
            replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, VECTOR_OPERATORS,
                    "ADD", "L" + VECTOR_OPERATORS + "$Associative;"));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FLOAT_VECTOR,
                    "reduceLanes",
                    "(L" + VECTOR_OPERATORS + "$Associative;)F",
                    false));
            replacement.add(new InsnNode(Opcodes.FADD));
            replacement.add(new VarInsnNode(Opcodes.FSTORE, body.reductionVar));

            // --- Scalar tail ---
            replacement.add(new VarInsnNode(Opcodes.ILOAD, boundLocal));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, loop.inductionVar));

            // Insert before original loop init, remove original init
            AbstractInsnNode initConst = findInitConst(insns, loop);
            AbstractInsnNode initStore = nextReal(insns, indexOf(initConst) + 1);

            instructions.insertBefore(initConst, replacement);
            instructions.remove(initConst);
            instructions.remove(initStore);
        }

        /**
         * Rewrites a gathered-access reduction loop:
         * {@code sum += data[indices[i]]} → Vector API with gathered fromArray + reduceLanes.
         *
         * <p>C2 cannot auto-vectorize gathered (indirect) access patterns, so this
         * transformation provides a significant speedup via the Vector API's
         * {@code FloatVector.fromArray(species, data, 0, indices, i)} overload.</p>
         */
        private void rewriteGatheredLoop(LoopInfo loop, BodyAnalysis body) {
            AbstractInsnNode[] insns = instructions.toArray();

            int speciesLocal = maxLocals++;
            int boundLocal = maxLocals++;
            int accumVecLocal = maxLocals++;
            int tempVecLocal = maxLocals++;

            InsnList replacement = new InsnList();

            // --- SPECIES = FloatVector.SPECIES_PREFERRED ---
            replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, FLOAT_VECTOR,
                    "SPECIES_PREFERRED", "L" + VECTOR_SPECIES + ";"));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, speciesLocal));

            // --- bound = SPECIES.loopBound(n) ---
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(cloneBoundLoad(loop.boundInsn));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, VECTOR_SPECIES,
                    "loopBound", "(I)I", true));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, boundLocal));

            // --- accumVec = FloatVector.zero(SPECIES) ---
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FLOAT_VECTOR,
                    "zero",
                    "(L" + VECTOR_SPECIES + ";)L" + FLOAT_VECTOR + ";",
                    false));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, accumVecLocal));

            // --- Vector loop ---
            LabelNode vectorHead = new LabelNode();
            LabelNode vectorExit = new LabelNode();

            replacement.add(new InsnNode(Opcodes.ICONST_0));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, loop.inductionVar));

            replacement.add(vectorHead);
            replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
            replacement.add(new VarInsnNode(Opcodes.ILOAD, boundLocal));
            replacement.add(new JumpInsnNode(Opcodes.IF_ICMPGE, vectorExit));

            // Gathered load: FloatVector.fromArray(SPECIES, data, 0, indices, i)
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, body.gatheredDataLocal));
            replacement.add(new InsnNode(Opcodes.ICONST_0));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, body.gatheredIndexLocal));
            replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FLOAT_VECTOR,
                    "fromArray",
                    "(L" + VECTOR_SPECIES + ";[FI[II)L" + FLOAT_VECTOR + ";",
                    false));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, tempVecLocal));

            // accumVec = accumVec.add(tempVec)
            replacement.add(new VarInsnNode(Opcodes.ALOAD, accumVecLocal));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, tempVecLocal));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FLOAT_VECTOR,
                    "add",
                    "(L" + VECTOR_BASE + ";)L" + FLOAT_VECTOR + ";",
                    false));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, accumVecLocal));

            // i += SPECIES.length()
            replacement.add(new VarInsnNode(Opcodes.ILOAD, loop.inductionVar));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, speciesLocal));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, VECTOR_SPECIES,
                    "length", "()I", true));
            replacement.add(new InsnNode(Opcodes.IADD));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, loop.inductionVar));
            replacement.add(new JumpInsnNode(Opcodes.GOTO, vectorHead));

            replacement.add(vectorExit);

            // --- Reduce: reductionVar += accumVec.reduceLanes(VectorOperators.ADD) ---
            replacement.add(new VarInsnNode(Opcodes.FLOAD, body.reductionVar));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, accumVecLocal));
            replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, VECTOR_OPERATORS,
                    "ADD", "L" + VECTOR_OPERATORS + "$Associative;"));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FLOAT_VECTOR,
                    "reduceLanes",
                    "(L" + VECTOR_OPERATORS + "$Associative;)F",
                    false));
            replacement.add(new InsnNode(Opcodes.FADD));
            replacement.add(new VarInsnNode(Opcodes.FSTORE, body.reductionVar));

            // --- Scalar tail ---
            replacement.add(new VarInsnNode(Opcodes.ILOAD, boundLocal));
            replacement.add(new VarInsnNode(Opcodes.ISTORE, loop.inductionVar));

            // Insert before original loop init, remove original init
            AbstractInsnNode initConst = findInitConst(insns, loop);
            AbstractInsnNode initStore = nextReal(insns, indexOf(initConst) + 1);

            instructions.insertBefore(initConst, replacement);
            instructions.remove(initConst);
            instructions.remove(initStore);
        }

        // ====================================================================
        // Utility methods
        // ====================================================================

        private static boolean isIntConst0(AbstractInsnNode insn) {
            if (insn.getOpcode() == Opcodes.ICONST_0) return true;
            if (insn.getOpcode() == Opcodes.BIPUSH && ((IntInsnNode) insn).operand == 0)
                return true;
            if (insn.getOpcode() == Opcodes.SIPUSH && ((IntInsnNode) insn).operand == 0)
                return true;
            if (insn.getOpcode() == Opcodes.LDC && insn instanceof LdcInsnNode ldc
                && ldc.cst instanceof Integer val && val == 0) return true;
            return false;
        }

        /** Returns the next non-pseudo instruction (skipping labels/frames/line numbers). */
        private static AbstractInsnNode nextReal(AbstractInsnNode[] insns, int from) {
            for (int i = from; i < insns.length; i++) {
                if (insns[i].getOpcode() != -1) return insns[i];
            }
            return null;
        }

        /** Returns the index of the next non-pseudo instruction. */
        private static int nextRealIndex(AbstractInsnNode[] insns, int from) {
            for (int i = from; i < insns.length; i++) {
                if (insns[i].getOpcode() != -1) return i;
            }
            return -1;
        }

        /** Returns next instruction including pseudo instructions. */
        private static AbstractInsnNode nextInsn(AbstractInsnNode[] insns, int from) {
            if (from < insns.length) return insns[from];
            return null;
        }

        private int indexOf(AbstractInsnNode node) {
            return instructions.indexOf(node);
        }

        /** Clones a bound-loading instruction. */
        private static AbstractInsnNode cloneBoundLoad(AbstractInsnNode boundInsn) {
            if (boundInsn instanceof VarInsnNode vin) {
                return new VarInsnNode(vin.getOpcode(), vin.var);
            }
            if (boundInsn instanceof FieldInsnNode fin) {
                return new FieldInsnNode(fin.getOpcode(), fin.owner, fin.name, fin.desc);
            }
            return boundInsn.clone(null);
        }

        /** Maps scalar float opcode to Vector API method name. */
        private static String vectorMethodName(int opcode) {
            return switch (opcode) {
                case Opcodes.FADD -> "add";
                case Opcodes.FSUB -> "sub";
                case Opcodes.FMUL -> "mul";
                case Opcodes.FDIV -> "div";
                default -> "add";
            };
        }

        /** Finds the ICONST_0 that initializes the induction variable for this loop. */
        private AbstractInsnNode findInitConst(AbstractInsnNode[] insns, LoopInfo loop) {
            // Walk backward from headLabel to find ICONST_0 + ISTORE inductionVar
            int headIdx = indexOf(loop.headLabel);
            for (int i = headIdx - 1; i >= 0; i--) {
                if (insns[i].getOpcode() == Opcodes.ISTORE
                    && insns[i] instanceof VarInsnNode vin
                    && vin.var == loop.inductionVar) {
                    // The const should be right before
                    for (int j = i - 1; j >= 0; j--) {
                        if (insns[j].getOpcode() == -1) continue;
                        if (isIntConst0(insns[j])) return insns[j];
                        break;
                    }
                }
            }
            // Fallback: return first instruction (should not happen for valid loops)
            return insns[0];
        }
    }
}
