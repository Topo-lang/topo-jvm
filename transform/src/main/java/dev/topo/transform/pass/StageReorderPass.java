package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Reorders method invocations within a method body to match declared stage ordering.
 * Uses the ASM Tree API (MethodNode) to load instructions into memory, sort
 * reorderable call blocks by stage number, then emit the reordered sequence.
 *
 * <p>A "reorderable group" is a consecutive sequence of void static/virtual call blocks
 * where each call has a known stage number and takes only simple loaded arguments.
 * Within each group, blocks are sorted by ascending stage order.</p>
 *
 * <p>Conservative safety: any instruction pattern that is not clearly a simple
 * load+invoke block breaks the current group. Data dependencies (stores, non-void
 * returns, stack manipulation) prevent reordering.</p>
 */
// @category: INFRA
public class StageReorderPass implements BasePass {
    private final JsonObject config;
    private final JsonObject metadata;

    public StageReorderPass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
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
                return new ReorderingMethodNode(Opcodes.ASM9, access, name, descriptor,
                        signature, exceptions, downstream);
            }
        };
    }

    /**
     * A block of instructions representing argument loads followed by a single
     * method invocation. All nodes are stored in order so they can be detached
     * and re-inserted during reordering.
     */
    private static class InsnBlock {
        final List<AbstractInsnNode> nodes;
        final int stageOrder;

        InsnBlock(List<AbstractInsnNode> nodes, int stageOrder) {
            this.nodes = nodes;
            this.stageOrder = stageOrder;
        }
    }

    /**
     * Extends MethodNode to collect all instructions via the normal Tree API,
     * then reorder eligible call sequences before writing to the downstream visitor.
     */
    private class ReorderingMethodNode extends MethodNode {
        private final MethodVisitor downstream;

        ReorderingMethodNode(int api, int access, String name, String descriptor,
                            String signature, String[] exceptions, MethodVisitor downstream) {
            super(api, access, name, descriptor, signature, exceptions);
            this.downstream = downstream;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            reorderInstructions();
            accept(downstream);
        }

        private void reorderInstructions() {
            Map<String, Integer> stageMap = buildStageMap();
            if (stageMap.isEmpty()) return;

            InsnList insns = this.instructions;
            if (insns.size() == 0) return;

            // Collect reorderable groups
            List<List<InsnBlock>> groups = new ArrayList<>();
            List<InsnBlock> currentGroup = new ArrayList<>();

            AbstractInsnNode node = insns.getFirst();
            while (node != null) {
                InsnBlock block = tryParseBlock(node, stageMap);
                if (block != null) {
                    currentGroup.add(block);
                    // Advance past this block
                    node = block.nodes.get(block.nodes.size() - 1).getNext();
                } else {
                    if (currentGroup.size() > 1) {
                        groups.add(new ArrayList<>(currentGroup));
                    }
                    currentGroup.clear();
                    node = node.getNext();
                }
            }
            if (currentGroup.size() > 1) {
                groups.add(currentGroup);
            }

            // Sort each group and replace in instruction list
            for (var group : groups) {
                List<InsnBlock> sorted = new ArrayList<>(group);
                sorted.sort(Comparator.comparingInt(b -> b.stageOrder));

                // Check if already in order (identity comparison)
                boolean needsReorder = false;
                for (int i = 0; i < group.size(); i++) {
                    if (group.get(i) != sorted.get(i)) {
                        needsReorder = true;
                        break;
                    }
                }
                if (!needsReorder) continue;

                // Remember the node before the first block (insertion anchor)
                AbstractInsnNode beforeGroup = group.get(0).nodes.get(0).getPrevious();

                // Remove all block nodes from the instruction list
                for (var blk : group) {
                    for (var n : blk.nodes) {
                        insns.remove(n);
                    }
                }

                // Re-insert in sorted order
                AbstractInsnNode insertAfter = beforeGroup;
                for (var blk : sorted) {
                    for (var n : blk.nodes) {
                        if (insertAfter == null) {
                            insns.insert(n);
                        } else {
                            insns.insert(insertAfter, n);
                        }
                        insertAfter = n;
                    }
                }
            }
        }

        /**
         * Attempts to parse a reorderable instruction block starting at {@code start}.
         * A block is a sequence of simple load instructions followed by a void
         * INVOKESTATIC or INVOKEVIRTUAL with a known stage number.
         *
         * @return the parsed block, or {@code null} if the pattern does not match
         */
        private InsnBlock tryParseBlock(AbstractInsnNode start, Map<String, Integer> stageMap) {
            List<AbstractInsnNode> nodes = new ArrayList<>();
            AbstractInsnNode cur = start;

            // Collect consecutive load/constant instructions
            while (cur != null && isLoadOrConstant(cur)) {
                nodes.add(cur);
                cur = cur.getNext();
            }

            // Next instruction must be an invoke
            if (cur == null) return null;
            if (cur.getOpcode() != Opcodes.INVOKESTATIC && cur.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                return null;
            }

            MethodInsnNode invoke = (MethodInsnNode) cur;

            // Must be void return
            if (!invoke.desc.endsWith(")V")) return null;

            // Look up stage (try class-qualified then namespace-level)
            Integer stage = QualifiedNameMatch.get(stageMap, invoke.owner, invoke.name);
            if (stage == null) return null;

            nodes.add(invoke);
            return new InsnBlock(nodes, stage);
        }

        /**
         * Returns true if the instruction is a simple value-loading instruction
         * that pushes a single value onto the stack without side effects.
         */
        private boolean isLoadOrConstant(AbstractInsnNode insn) {
            int opcode = insn.getOpcode();
            // Skip pseudo-instructions (labels, frames, line numbers)
            // These have opcode -1; they are not loads.
            if (opcode < 0) return false;

            return switch (opcode) {
                case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.LLOAD,
                     Opcodes.FLOAD, Opcodes.DLOAD -> true;
                case Opcodes.LDC -> true;
                case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1,
                     Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4,
                     Opcodes.ICONST_5 -> true;
                case Opcodes.LCONST_0, Opcodes.LCONST_1 -> true;
                case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> true;
                case Opcodes.DCONST_0, Opcodes.DCONST_1 -> true;
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> true;
                case Opcodes.ACONST_NULL -> true;
                default -> false;
            };
        }
    }

    /**
     * Builds a map from qualified function name to stage number,
     * extracted from the metadata logicBlocks.
     */
    private Map<String, Integer> buildStageMap() {
        Map<String, Integer> map = new HashMap<>();
        if (!metadata.has("logicBlocks")) return map;

        for (var entry : metadata.getAsJsonObject("logicBlocks").entrySet()) {
            var block = entry.getValue().getAsJsonObject();
            if (!block.has("calledFunctions") || !block.has("stages")) continue;
            var calledFunctions = block.getAsJsonArray("calledFunctions");
            var stages = block.getAsJsonArray("stages");
            String blockQName = block.get("qualifiedName").getAsString();
            String namespace = blockQName.contains("::")
                ? blockQName.substring(0, blockQName.lastIndexOf("::"))
                : "";
            for (int i = 0; i < calledFunctions.size() && i < stages.size(); i++) {
                String simpleName = calledFunctions.get(i).getAsString();
                String qualifiedCallee = namespace.isEmpty() ? simpleName : namespace + "::" + simpleName;
                map.put(qualifiedCallee, stages.get(i).getAsInt());
            }
        }
        return map;
    }
}
