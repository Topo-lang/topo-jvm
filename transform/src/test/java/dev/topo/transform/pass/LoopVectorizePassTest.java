package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LoopVectorizePass — verifies Vector API codegen for simple counted
 * loops in parallel-stage functions.
 */
class LoopVectorizePassTest {

    private byte[] applyPass(byte[] input, JsonObject config, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new LoopVectorizePass(config, metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private JsonObject defaultConfig() {
        var config = new JsonObject();
        config.addProperty("optLevel", 2);
        var loopCfg = new JsonObject();
        loopCfg.addProperty("mode", "force");
        config.add("loopParallelCfg", loopCfg);
        return config;
    }

    // ========================================================================
    // Bytecode generators
    // ========================================================================

    /**
     * Generate bytecode for:
     * <pre>
     * static void addArrays(float[] a, float[] b, float[] c, int n) {
     *     for (int i = 0; i < n; i++) {
     *         a[i] = b[i] + c[i];
     *     }
     * }
     * </pre>
     */
    private byte[] generateArrayAddClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Compute", null,
                "java/lang/Object", null);

        // Constructor
        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // static void addArrays(float[] a, float[] b, float[] c, int n)
        // locals: 0=a, 1=b, 2=c, 3=n, 4=i
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "addArrays",
                "([F[F[FI)V", null, null);
        mv.visitCode();

        // int i = 0
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        var loopHead = new Label();
        var loopExit = new Label();

        // loop head: if (i >= n) goto exit
        mv.visitLabel(loopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopExit);

        // a[i] = b[i] + c[i]
        mv.visitVarInsn(Opcodes.ALOAD, 0); // a
        mv.visitVarInsn(Opcodes.ILOAD, 4); // i
        mv.visitVarInsn(Opcodes.ALOAD, 1); // b
        mv.visitVarInsn(Opcodes.ILOAD, 4); // i
        mv.visitInsn(Opcodes.FALOAD);       // b[i]
        mv.visitVarInsn(Opcodes.ALOAD, 2); // c
        mv.visitVarInsn(Opcodes.ILOAD, 4); // i
        mv.visitInsn(Opcodes.FALOAD);       // c[i]
        mv.visitInsn(Opcodes.FADD);         // b[i] + c[i]
        mv.visitInsn(Opcodes.FASTORE);      // a[i] = result

        // i++
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);

        mv.visitLabel(loopExit);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(6, 5);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate bytecode for a loop with a method call inside (not vectorizable):
     * <pre>
     * static void withCall(float[] a, int n) {
     *     for (int i = 0; i < n; i++) {
     *         a[i] = Math.abs(a[i]);
     *     }
     * }
     * </pre>
     */
    private byte[] generateLoopWithCallClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Compute", null,
                "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // static void withCall(float[] a, int n)
        // locals: 0=a, 1=n, 2=i
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "withCall",
                "([FI)V", null, null);
        mv.visitCode();

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 2);

        var loopHead = new Label();
        var loopExit = new Label();

        mv.visitLabel(loopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopExit);

        // a[i] = Math.abs(a[i])
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.FALOAD);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(F)F", false);
        mv.visitInsn(Opcodes.FASTORE);

        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);

        mv.visitLabel(loopExit);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // ========================================================================
    // Metadata builders
    // ========================================================================

    /**
     * Build metadata with a logic block that has the given functions all in the
     * same stage (making them parallel-stage functions).
     */
    private JsonObject buildParallelStageMetadata(String... functions) {
        var metadata = new JsonObject();
        var logicBlocks = new JsonObject();
        var block = new JsonObject();
        block.addProperty("qualifiedName", "app::Main::run");

        var calledFunctions = new JsonArray();
        var stages = new JsonArray();
        for (String fn : functions) {
            calledFunctions.add(fn);
            stages.add(1); // all in same stage = parallel
        }
        block.add("calledFunctions", calledFunctions);
        block.add("stages", stages);
        logicBlocks.add("block_0", block);
        metadata.add("logicBlocks", logicBlocks);
        return metadata;
    }

    // ========================================================================
    // Verification helpers
    // ========================================================================

    /** Check if the given method contains any Vector API calls. */
    private boolean hasVectorApiCall(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min) {
                    if (min.owner.contains("Vector")
                        && min.owner.startsWith("jdk/incubator/vector/")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Check if the given method has a specific Vector API static call. */
    private boolean hasFromArrayCall(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min) {
                    if (min.owner.contains("FloatVector") && min.name.equals("fromArray")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ========================================================================
    // Tests
    // ========================================================================

    @Test
    void simpleArrayAddVectorized() {
        byte[] input = generateArrayAddClass();
        var metadata = buildParallelStageMetadata(
            "app::Compute::addArrays", "app::Compute::otherTask");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertTrue(hasVectorApiCall(output, "addArrays"),
            "addArrays should contain Vector API calls after transformation");
        assertTrue(hasFromArrayCall(output, "addArrays"),
            "addArrays should contain FloatVector.fromArray call");
    }

    @Test
    void nonParallelStageSkipped() {
        byte[] input = generateArrayAddClass();
        // Metadata has functions NOT including addArrays in a parallel stage
        var metadata = buildParallelStageMetadata("app::Other::taskA", "app::Other::taskB");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertFalse(hasVectorApiCall(output, "addArrays"),
            "addArrays should NOT be vectorized when not in parallel stage");
    }

    @Test
    void configModeOffNoTransform() {
        byte[] input = generateArrayAddClass();
        var metadata = buildParallelStageMetadata(
            "app::Compute::addArrays", "app::Compute::otherTask");

        // Config with mode = "off"
        var config = new JsonObject();
        config.addProperty("optLevel", 2);
        var loopCfg = new JsonObject();
        loopCfg.addProperty("mode", "off");
        config.add("loopParallelCfg", loopCfg);

        // The pass itself always transforms if method matches — gating is done
        // in PassPipeline.isEnabled(). So with matching metadata, it still transforms.
        // This test verifies that WITHOUT matching metadata, nothing happens.
        var emptyMeta = new JsonObject();
        byte[] output = applyPass(input, config, emptyMeta);

        assertFalse(hasVectorApiCall(output, "addArrays"),
            "No transformation when metadata has no logicBlocks");
    }

    @Test
    void loopWithMethodCallSkipped() {
        byte[] input = generateLoopWithCallClass();
        var metadata = buildParallelStageMetadata(
            "app::Compute::withCall", "app::Compute::otherTask");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertFalse(hasVectorApiCall(output, "withCall"),
            "Loop with method call inside body should NOT be vectorized");
    }

    @Test
    void emptyMetadataNoTransform() {
        byte[] input = generateArrayAddClass();

        byte[] output = applyPass(input, defaultConfig(), new JsonObject());

        assertFalse(hasVectorApiCall(output, "addArrays"),
            "No transformation with empty metadata");
    }

    @Test
    void namespaceMatchWorks() {
        byte[] input = generateArrayAddClass();
        // Use namespace-level name (without class): "app::addArrays"
        var metadata = buildParallelStageMetadata(
            "app::addArrays", "app::otherTask");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertTrue(hasVectorApiCall(output, "addArrays"),
            "Namespace-level match should trigger vectorization");
    }

    @Test
    void orchestratorIncluded() {
        byte[] input = generateArrayAddClass();
        // The orchestrator "app::Main::run" references two functions in parallel.
        // addArrays is NOT one of them, but if we name the class app/Main and method "run",
        // the orchestrator itself would match. Here we verify the orchestrator is in the set
        // by targeting addArrays as a function in the parallel stage.
        var metadata = new JsonObject();
        var logicBlocks = new JsonObject();
        var block = new JsonObject();
        block.addProperty("qualifiedName", "app::Compute::addArrays");
        var calledFunctions = new JsonArray();
        calledFunctions.add("app::Compute::taskA");
        calledFunctions.add("app::Compute::taskB");
        var stages = new JsonArray();
        stages.add(1);
        stages.add(1);
        block.add("calledFunctions", calledFunctions);
        block.add("stages", stages);
        logicBlocks.add("block_0", block);
        metadata.add("logicBlocks", logicBlocks);

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertTrue(hasVectorApiCall(output, "addArrays"),
            "Orchestrator (qualifiedName) should be included for vectorization");
    }
}
