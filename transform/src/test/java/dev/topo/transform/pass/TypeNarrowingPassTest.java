package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TypeNarrowingPass — verifies Array.get() → rawData()[i] direct access
 * transformation for {@code dev.topo.Array<T>} containers with CHECKCAST patterns.
 */
class TypeNarrowingPassTest {

    private byte[] applyPass(byte[] input, JsonObject config, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new TypeNarrowingPass(config, metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private JsonObject configWithMode(String mode) {
        var config = new JsonObject();
        var typeNarrowingCfg = new JsonObject();
        typeNarrowingCfg.addProperty("mode", mode);
        config.add("typeNarrowingCfg", typeNarrowingCfg);
        return config;
    }

    // =========================================================================
    // Test bytecode generators
    // =========================================================================

    /**
     * Generate: T result = (T) array.get(index);
     * Bytecode: ALOAD 1, ILOAD 2, INVOKEVIRTUAL Array.get, CHECKCAST app/Particle
     */
    private byte[] generateArrayGetWithCast() {
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

        // public Object process(Array array, int index)
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "process",
                "(Ldev/topo/Array;I)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);   // array
        mv.visitVarInsn(Opcodes.ILOAD, 2);    // index
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate: Object result = array.get(index); — no CHECKCAST after get().
     */
    private byte[] generateArrayGetWithoutCast() {
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

        // public Object process(Array array, int index)
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "process",
                "(Ldev/topo/Array;I)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);   // array
        mv.visitVarInsn(Opcodes.ILOAD, 2);    // index
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        // No CHECKCAST — just return the Object directly
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate two Array.get+CHECKCAST sites in the same method:
     * {@code Particle a = (Particle) array.get(i); Particle b = (Particle) array.get(j);}
     */
    private byte[] generateMultipleSites() {
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

        // public Object process(Array array, int i, int j)
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "process",
                "(Ldev/topo/Array;II)Ljava/lang/Object;", null, null);
        mv.visitCode();

        // First: (Particle) array.get(i) → store to local 4
        mv.visitVarInsn(Opcodes.ALOAD, 1);   // array
        mv.visitVarInsn(Opcodes.ILOAD, 2);    // i
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        // Second: (Particle) array.get(j) → store to local 5
        mv.visitVarInsn(Opcodes.ALOAD, 1);   // array
        mv.visitVarInsn(Opcodes.ILOAD, 3);    // j
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "app/Particle");
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        // Return first
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(4, 6);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // =========================================================================
    // Verification helpers
    // =========================================================================

    private boolean hasRawDataCall(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min) {
                    if ("rawData".equals(min.name)
                        && "dev/topo/Array".equals(min.owner)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int countArrayGetCalls(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min) {
                    if ("get".equals(min.name)
                        && "dev/topo/Array".equals(min.owner)
                        && "(I)Ljava/lang/Object;".equals(min.desc)) {
                        count++;
                    }
                }
            }
            return count;
        }
        return -1;
    }

    private boolean hasAALOAD(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn.getOpcode() == Opcodes.AALOAD) return true;
            }
        }
        return false;
    }

    private boolean hasCheckcast(byte[] classBytes, String methodName, String type) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof TypeInsnNode tin) {
                    if (tin.getOpcode() == Opcodes.CHECKCAST && tin.desc.equals(type)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int countRawDataCalls(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min) {
                    if ("rawData".equals(min.name)
                        && "dev/topo/Array".equals(min.owner)) {
                        count++;
                    }
                }
            }
            return count;
        }
        return -1;
    }

    private int countAALOAD(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn.getOpcode() == Opcodes.AALOAD) count++;
            }
            return count;
        }
        return -1;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void testOffModeIsNoOp() {
        byte[] input = generateArrayGetWithCast();

        byte[] output = applyPass(input, configWithMode("off"), new JsonObject());

        // Off mode: Array.get() calls should be preserved, no rawData() calls
        assertEquals(1, countArrayGetCalls(output, "process"),
            "Array.get() should be preserved when mode is off");
        assertFalse(hasRawDataCall(output, "process"),
            "rawData() should not be present when mode is off");
        assertFalse(hasAALOAD(output, "process"),
            "AALOAD should not be present when mode is off");
        assertTrue(hasCheckcast(output, "process", "app/Particle"),
            "CHECKCAST should be preserved when mode is off");
    }

    @Test
    void testAutoModeTransforms() {
        byte[] input = generateArrayGetWithCast();

        byte[] output = applyPass(input, configWithMode("auto"), new JsonObject());

        // Auto mode should apply the transformation (both auto and force are active)
        assertEquals(0, countArrayGetCalls(output, "process"),
            "Array.get() should be removed after type narrowing");
        assertTrue(hasRawDataCall(output, "process"),
            "rawData() should be present after type narrowing");
        assertTrue(hasAALOAD(output, "process"),
            "AALOAD should be present after type narrowing");
        assertTrue(hasCheckcast(output, "process", "app/Particle"),
            "CHECKCAST should be preserved as type guard");
    }

    @Test
    void testForceModeTransforms() {
        byte[] input = generateArrayGetWithCast();

        byte[] output = applyPass(input, configWithMode("force"), new JsonObject());

        // Force mode should apply the transformation
        assertEquals(0, countArrayGetCalls(output, "process"),
            "Array.get() should be removed after type narrowing");
        assertTrue(hasRawDataCall(output, "process"),
            "rawData() should be present after type narrowing");
        assertTrue(hasAALOAD(output, "process"),
            "AALOAD should be present after type narrowing");
        assertTrue(hasCheckcast(output, "process", "app/Particle"),
            "CHECKCAST should be preserved as type guard");
    }

    @Test
    void testNoCheckcastNotTransformed() {
        byte[] input = generateArrayGetWithoutCast();

        byte[] output = applyPass(input, configWithMode("force"), new JsonObject());

        // Without CHECKCAST after get(), the pattern should not match
        assertEquals(1, countArrayGetCalls(output, "process"),
            "Array.get() should be preserved when no CHECKCAST follows");
        assertFalse(hasRawDataCall(output, "process"),
            "rawData() should not be present when pattern does not match");
        assertFalse(hasAALOAD(output, "process"),
            "AALOAD should not be present when pattern does not match");
    }

    @Test
    void testMultipleSitesTransformed() {
        byte[] input = generateMultipleSites();

        byte[] output = applyPass(input, configWithMode("force"), new JsonObject());

        // Both Array.get+CHECKCAST sites should be transformed
        assertEquals(0, countArrayGetCalls(output, "process"),
            "All Array.get() calls should be removed");
        assertEquals(2, countRawDataCalls(output, "process"),
            "Two rawData() calls should be present (one per site)");
        assertEquals(2, countAALOAD(output, "process"),
            "Two AALOAD instructions should be present (one per site)");
        assertTrue(hasCheckcast(output, "process", "app/Particle"),
            "CHECKCAST should be preserved as type guard");
    }
}
