package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

class ObservabilityPassTest {

    private static byte[] applyPass(byte[] input, JsonObject config, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ObservabilityPass pass = new ObservabilityPass(config, metadata);
        ClassVisitor visitor = pass.createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * Generate a minimal class with a single void no-arg method.
     */
    private static byte[] generateClass(String className, String methodName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null,
                "java/lang/Object", null);

        // Default constructor
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // Target method: void methodName()
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Build metadata in the new Object format with logicBlocks containing stage info.
     * The qualifiedName of the function is used to derive the logic block structure.
     * The block's qualifiedName uses the namespace (class) + a logic block name,
     * and calledFunctions contains the simple name of the target function.
     */
    private static JsonObject buildMetadata(String qualifiedName, int stage) {
        JsonObject metadata = new JsonObject();

        // Extract namespace and simple name from "app::Main::init"
        int lastSep = qualifiedName.lastIndexOf("::");
        String namespace = lastSep >= 0 ? qualifiedName.substring(0, lastSep) : "";
        String simpleName = lastSep >= 0 ? qualifiedName.substring(lastSep + 2) : qualifiedName;

        // Build logicBlocks with stage info.
        // Block qualifiedName uses namespace so calledFunctions resolve correctly.
        JsonObject logicBlocks = new JsonObject();
        JsonObject block = new JsonObject();
        // Use namespace + "::orchestrator" as the block qname so namespace derivation works
        String blockQName = namespace + "::orchestrator";
        block.addProperty("qualifiedName", blockQName);
        block.addProperty("simpleName", "orchestrator");
        JsonArray calledFunctions = new JsonArray();
        calledFunctions.add(simpleName);
        block.add("calledFunctions", calledFunctions);
        JsonArray stages = new JsonArray();
        stages.add(stage);
        block.add("stages", stages);
        block.addProperty("isPipeline", false);
        block.add("edges", new JsonArray());
        logicBlocks.add(blockQName, block);
        metadata.add("logicBlocks", logicBlocks);

        return metadata;
    }

    private static MethodNode findMethod(byte[] bytecode, String methodName) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytecode).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(methodName)) return mn;
        }
        return null;
    }

    @Test
    void stagedMethodInstrumented() {
        String className = "app/Main";
        String methodName = "init";
        byte[] input = generateClass(className, methodName);
        JsonObject metadata = buildMetadata("app::Main::init", 1);

        byte[] output = applyPass(input, new JsonObject(), metadata);
        MethodNode mn = findMethod(output, methodName);
        assertNotNull(mn, "Method should exist in transformed class");

        // Verify NEW dev/topo/Observe$StageEvent is present
        boolean hasNew = false;
        boolean hasEndStage = false;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof TypeInsnNode tin
                    && tin.getOpcode() == Opcodes.NEW
                    && "dev/topo/Observe$StageEvent".equals(tin.desc)) {
                hasNew = true;
            }
            if (insn instanceof MethodInsnNode min
                    && min.getOpcode() == Opcodes.INVOKESTATIC
                    && "dev/topo/Observe".equals(min.owner)
                    && "endStage".equals(min.name)) {
                hasEndStage = true;
            }
        }
        assertTrue(hasNew, "Transformed method should contain NEW dev/topo/Observe$StageEvent");
        assertTrue(hasEndStage, "Transformed method should contain INVOKESTATIC Observe.endStage before RETURN");
    }

    @Test
    void nonStagedMethodUntouched() {
        String className = "app/Main";
        String methodName = "helper";
        byte[] input = generateClass(className, methodName);

        // Metadata has no entry for this method
        JsonObject metadata = buildMetadata("app::Main::otherMethod", 1);

        byte[] output = applyPass(input, new JsonObject(), metadata);
        MethodNode mn = findMethod(output, methodName);
        assertNotNull(mn, "Method should exist in transformed class");

        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof TypeInsnNode tin && tin.getOpcode() == Opcodes.NEW) {
                assertNotEquals("dev/topo/Observe$StageEvent", tin.desc,
                        "Non-staged method must not contain StageEvent instructions");
            }
            if (insn instanceof MethodInsnNode min) {
                assertNotEquals("endStage", min.name,
                        "Non-staged method must not contain endStage call");
            }
        }
    }

    @Test
    void stageOrderSet() {
        String className = "app/Main";
        String methodName = "process";
        byte[] input = generateClass(className, methodName);
        JsonObject metadata = buildMetadata("app::Main::process", 2);

        byte[] output = applyPass(input, new JsonObject(), metadata);
        MethodNode mn = findMethod(output, methodName);
        assertNotNull(mn, "Method should exist in transformed class");

        // Look for the sequence: push int 2 followed by PUTFIELD stageOrder
        boolean stageOrderFound = false;
        AbstractInsnNode prev = null;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof FieldInsnNode fin
                    && fin.getOpcode() == Opcodes.PUTFIELD
                    && "stageOrder".equals(fin.name)
                    && "dev/topo/Observe$StageEvent".equals(fin.owner)) {
                // The instruction before PUTFIELD should push the int constant 2
                // (prev might be ICONST_2 or BIPUSH 2 etc.)
                assertNotNull(prev, "There should be an instruction before PUTFIELD stageOrder");
                int pushedValue = extractIntConstant(prev);
                assertEquals(2, pushedValue,
                        "stageOrder field should be set to 2 from metadata");
                stageOrderFound = true;
            }
            // Skip frame/label/line pseudo-instructions when tracking prev
            if (insn.getType() != AbstractInsnNode.FRAME
                    && insn.getType() != AbstractInsnNode.LABEL
                    && insn.getType() != AbstractInsnNode.LINE) {
                prev = insn;
            }
        }
        assertTrue(stageOrderFound, "Transformed method should set stageOrder field");
    }

    /**
     * Extract the integer constant from an instruction node, or return Integer.MIN_VALUE
     * if the node is not a recognized int-push instruction.
     */
    private static int extractIntConstant(AbstractInsnNode insn) {
        if (insn instanceof InsnNode) {
            int op = insn.getOpcode();
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
                return op - Opcodes.ICONST_0;
            }
        }
        if (insn instanceof IntInsnNode iin) {
            if (iin.getOpcode() == Opcodes.BIPUSH || iin.getOpcode() == Opcodes.SIPUSH) {
                return iin.operand;
            }
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) {
            return i;
        }
        return Integer.MIN_VALUE;
    }
}
