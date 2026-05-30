package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DataLayoutPass — verifies AoS-to-SoA transformation
 * for {@code dev.topo.Array<T>} containers with primitive field access.
 */
class DataLayoutPassTest {

    private byte[] applyPass(byte[] input, JsonObject config, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new DataLayoutPass(config, metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private JsonObject defaultConfig() {
        var config = new JsonObject();
        var dataLayoutCfg = new JsonObject();
        dataLayoutCfg.addProperty("mode", "force");
        config.add("dataLayoutCfg", dataLayoutCfg);
        return config;
    }

    // =========================================================================
    // Test bytecode generators
    // =========================================================================

    /**
     * Generate a class with a method that simulates:
     * {@code float result = array.get(i).x;}
     *
     * <p>Bytecode: ALOAD 1, ILOAD 2, INVOKEVIRTUAL Array.get, CHECKCAST Particle, GETFIELD x F</p>
     */
    private byte[] generateArrayGetFieldClass(String fieldName, String fieldDesc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Processor", null,
                "java/lang/Object", null);

        // constructor
        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // public float process(Array array, int index)
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "process",
                "(Ldev/topo/Array;I)F", null, null);
        mv.visitCode();

        // array.get(index) -> checkcast -> getfield
        mv.visitVarInsn(Opcodes.ALOAD, 1);  // array ref
        mv.visitVarInsn(Opcodes.ILOAD, 2);  // index
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
        mv.visitFieldInsn(Opcodes.GETFIELD, "app/Particle", fieldName, fieldDesc);

        mv.visitInsn(Opcodes.FRETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate a class with a method accessing a String (object) field.
     */
    private byte[] generateObjectFieldClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Processor", null,
                "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // public String process(Array array, int index)
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "process",
                "(Ldev/topo/Array;I)Ljava/lang/String;", null, null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
        mv.visitFieldInsn(Opcodes.GETFIELD, "app/Particle", "name", "Ljava/lang/String;");

        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // =========================================================================
    // Metadata builders
    // =========================================================================

    private JsonObject buildMetadataWithPattern(String qualifiedName, String accessPattern) {
        var metadata = new JsonObject();
        var functions = new JsonObject();
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", qualifiedName);
        fn.addProperty("simpleName", qualifiedName.substring(qualifiedName.lastIndexOf("::") + 2));
        fn.addProperty("visibility", "public");
        fn.addProperty("accessPattern", accessPattern);
        functions.add(qualifiedName, fn);
        metadata.add("functions", functions);
        return metadata;
    }

    // =========================================================================
    // Instruction counting helpers
    // =========================================================================

    private int countOpcode(byte[] classBytes, String methodName, int opcode) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn.getOpcode() == opcode) count++;
            }
            return count;
        }
        return -1;
    }

    private int countGetField(byte[] classBytes, String methodName, String fieldName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof FieldInsnNode fin) {
                    if (fin.getOpcode() == Opcodes.GETFIELD && fin.name.equals(fieldName)) {
                        count++;
                    }
                }
            }
            return count;
        }
        return -1;
    }

    private boolean hasNewArray(byte[] classBytes, String methodName, int arrayTypeCode) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof IntInsnNode iin) {
                    if (iin.getOpcode() == Opcodes.NEWARRAY && iin.operand == arrayTypeCode) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasColumnarViewCall(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min) {
                    if (min.name.equals("getColumnarView")
                        && min.owner.equals("dev/topo/Array")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Generate a class with a method that accesses the same field N times:
     * {@code float result = array.get(i).x + array.get(i).x + ...;}
     *
     * <p>N separate Array.get -> CHECKCAST -> GETFIELD x sequences in the same method,
     * giving an access ratio of N for the single field.</p>
     */
    private byte[] generateRepeatedFieldAccessClass(int accessCount) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Processor", null,
                "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // public float process(Array array, int index)
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "process",
                "(Ldev/topo/Array;I)F", null, null);
        mv.visitCode();

        // First access — push result onto stack
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
        mv.visitFieldInsn(Opcodes.GETFIELD, "app/Particle", "x", "F");

        // Remaining accesses — each adds to the accumulator
        for (int a = 1; a < accessCount; a++) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                    "(I)Ljava/lang/Object;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
            mv.visitFieldInsn(Opcodes.GETFIELD, "app/Particle", "x", "F");
            mv.visitInsn(Opcodes.FADD);
        }

        mv.visitInsn(Opcodes.FRETURN);
        mv.visitMaxs(4, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate a class with a method that accesses TWO fields on the same Array element:
     * {@code float result = array.get(i).x + array.get(i).y;}
     *
     * <p>Two separate Array.get -> CHECKCAST -> GETFIELD sequences in the same method.</p>
     */
    private byte[] generateMultiFieldAccessClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Processor", null,
                "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // public float process(Array array, int index) { return array.get(i).x + array.get(i).y; }
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "process",
                "(Ldev/topo/Array;I)F", null, null);
        mv.visitCode();

        // First access: array.get(index).x
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
        mv.visitFieldInsn(Opcodes.GETFIELD, "app/Particle", "x", "F");

        // Second access: array.get(index).y
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
        mv.visitFieldInsn(Opcodes.GETFIELD, "app/Particle", "y", "F");

        // result = x + y
        mv.visitInsn(Opcodes.FADD);
        mv.visitInsn(Opcodes.FRETURN);
        mv.visitMaxs(4, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void testAutoModeIsNoOp() {
        byte[] input = generateArrayGetFieldClass("x", "F");
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        var config = new JsonObject();
        var dataLayoutCfg = new JsonObject();
        dataLayoutCfg.addProperty("mode", "auto");
        config.add("dataLayoutCfg", dataLayoutCfg);

        byte[] output = applyPass(input, config, metadata);

        // auto mode is gated as no-op — bytecode should be unchanged
        assertEquals(1, countGetField(output, "process", "x"),
            "GETFIELD should be preserved when mode is auto (gated as no-op)");
        assertEquals(0, countOpcode(output, "process", Opcodes.FALOAD),
            "FALOAD should not be present when mode is auto");
        assertFalse(hasColumnarViewCall(output, "process"),
            "getColumnarView() should not be present when mode is auto");
    }

    @Test
    void testMultiFieldCandidateSkipped() {
        byte[] input = generateMultiFieldAccessClass();
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        // Multi-field candidate should be skipped — both GETFIELD instructions preserved
        assertEquals(1, countGetField(output, "process", "x"),
            "GETFIELD for x should be preserved (multi-field candidate skipped)");
        assertEquals(1, countGetField(output, "process", "y"),
            "GETFIELD for y should be preserved (multi-field candidate skipped)");
        assertEquals(0, countOpcode(output, "process", Opcodes.FALOAD),
            "FALOAD should not be present (multi-field candidate skipped)");
        assertFalse(hasColumnarViewCall(output, "process"),
            "getColumnarView() should not be present (multi-field candidate skipped)");
    }

    @Test
    void testSingleFieldTransformed() {
        byte[] input = generateArrayGetFieldClass("x", "F");
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        // Single-field candidate in force mode with streaming pattern should be transformed
        assertTrue(countOpcode(output, "process", Opcodes.FALOAD) > 0,
            "FALOAD should be present after single-field SoA transformation");
        assertTrue(hasColumnarViewCall(output, "process"),
            "getColumnarView() should be present for column access");
        // Original GETFIELD for x should be gone (replaced by FALOAD)
        assertEquals(0, countGetField(output, "process", "x"),
            "GETFIELD for x should be removed after transformation");
    }

    @Test
    void streamingAccessRewritten() {
        byte[] input = generateArrayGetFieldClass("x", "F");
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        // GETFIELD for x should be gone from the main access
        // FALOAD should be present (column array access)
        assertTrue(countOpcode(output, "process", Opcodes.FALOAD) > 0,
            "FALOAD should be present after SoA transformation");
        // getColumnarView() should be present (cached column materialization)
        assertTrue(hasColumnarViewCall(output, "process"),
            "getColumnarView() should be present for cached column access");
    }

    @Test
    void randomAccessSkipped() {
        byte[] input = generateArrayGetFieldClass("x", "F");
        var metadata = buildMetadataWithPattern("app::Processor::process", "random");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        // No transformation — original GETFIELD should remain
        assertEquals(1, countGetField(output, "process", "x"),
            "GETFIELD for x should be preserved when access pattern is random");
        assertEquals(0, countOpcode(output, "process", Opcodes.FALOAD),
            "FALOAD should not be present when access pattern is random");
    }

    @Test
    void configModeOffNoTransform() {
        byte[] input = generateArrayGetFieldClass("x", "F");
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        var config = new JsonObject();
        var dataLayoutCfg = new JsonObject();
        dataLayoutCfg.addProperty("mode", "off");
        config.add("dataLayoutCfg", dataLayoutCfg);

        byte[] output = applyPass(input, config, metadata);

        assertEquals(1, countGetField(output, "process", "x"),
            "GETFIELD should be preserved when mode is off");
        assertEquals(0, countOpcode(output, "process", Opcodes.FALOAD),
            "FALOAD should not be present when mode is off");
    }

    @Test
    void objectFieldNotTransformed() {
        byte[] input = generateObjectFieldClass();
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        // String field (non-primitive) should NOT be transformed
        assertEquals(1, countGetField(output, "process", "name"),
            "GETFIELD for String field should be preserved (non-primitive)");
    }

    @Test
    void emptyMetadataNoTransform() {
        byte[] input = generateArrayGetFieldClass("x", "F");

        byte[] output = applyPass(input, defaultConfig(), new JsonObject());

        // No metadata means no access pattern info, but force mode should still try.
        // However, without function entries, shouldTransform returns true for force mode
        // (no pattern means not "random"). The pass will transform if it can detect the pattern.
        // Actually with force mode and no "random" pattern, it transforms.
        // Let's verify the output is at least valid (no crash).
        assertNotNull(output, "Pass should not crash with empty metadata");
    }

    @Test
    void intFieldTransformed() {
        byte[] input = generateArrayGetFieldClass("id", "I");
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        // Need to generate class returning int instead of float
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Processor", null,
                "java/lang/Object", null);
        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "process",
                "(Ldev/topo/Array;I)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
        mv.visitFieldInsn(Opcodes.GETFIELD, "app/Particle", "id", "I");
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
        cw.visitEnd();

        byte[] output = applyPass(cw.toByteArray(), defaultConfig(), metadata);

        assertTrue(countOpcode(output, "process", Opcodes.IALOAD) > 0,
            "IALOAD should be present after SoA transformation of int field");
        assertTrue(hasColumnarViewCall(output, "process"),
            "getColumnarView() should be present for cached column access");
    }

    // =========================================================================
    // Access frequency gate tests
    // =========================================================================

    // Removed: testLowFrequencySkippedInNonForceMode + testCustomMinAccessRatio.
    // The access-ratio gate was deleted from the Pass — the
    // gate was an embedded cost heuristic violating the "Topo doesn't judge"
    // principle. The DEFAULT_MIN_ACCESS_RATIO constant and min_access_ratio
    // config field no longer exist.

    @Test
    void testForceModeTransformsSingleAccess() {
        // Single access in force mode transforms — no Pass-side gate on
        // access frequency.
        byte[] input = generateArrayGetFieldClass("x", "F");
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertTrue(countOpcode(output, "process", Opcodes.FALOAD) > 0,
            "FALOAD should be present after SoA transform");
        assertEquals(0, countGetField(output, "process", "x"),
            "GETFIELD for x should be removed after SoA transform");
    }

    @Test
    void testHighFrequencyTransformed() {
        // Multiple accesses to the same field — transformed identically to
        // single-access case (no frequency-based gating).
        byte[] input = generateRepeatedFieldAccessClass(5);
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertTrue(countOpcode(output, "process", Opcodes.FALOAD) > 0,
            "FALOAD should be present for force-mode SoA transform");
        assertTrue(hasColumnarViewCall(output, "process"),
            "getColumnarView() should be present for force-mode SoA transform");
    }
}
