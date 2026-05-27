package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PrefetchPass — verifies prefetch hint insertion into
 * counted loops that iterate over {@code dev.topo.Array}.
 */
class PrefetchPassTest {

    private byte[] applyPass(byte[] input, JsonObject config, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new PrefetchPass(config, metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private JsonObject defaultConfig() {
        var config = new JsonObject();
        var prefetchCfg = new JsonObject();
        prefetchCfg.addProperty("mode", "force");
        prefetchCfg.addProperty("distance", 8);
        config.add("prefetchCfg", prefetchCfg);
        return config;
    }

    private JsonObject offConfig() {
        var config = new JsonObject();
        var prefetchCfg = new JsonObject();
        prefetchCfg.addProperty("mode", "off");
        config.add("prefetchCfg", prefetchCfg);
        return config;
    }

    // =========================================================================
    // Test bytecode generators
    // =========================================================================

    /**
     * Generate a class with a counted loop:
     * <pre>
     *   for (int i = 0; i < array.size(); i++) {
     *       array.get(i);
     *   }
     * </pre>
     */
    private byte[] generateCountedLoopClass() {
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

        // public void process(Array array)
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "process",
                "(Ldev/topo/Array;)V", null, null);
        mv.visitCode();

        // int i = 0
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 2);

        Label loopHead = new Label();
        Label loopEnd = new Label();

        // loopHead:
        mv.visitLabel(loopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "size", "()I", false);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);

        // body: array.get(i)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);

        // i++
        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);

        mv.visitLabel(loopEnd);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate a class with a simple method (no loop).
     */
    private byte[] generateNonLoopClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Simple", null,
                "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // public Object get(Array array, int index) { return array.get(index); }
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "get",
                "(Ldev/topo/Array;I)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Array", "get",
                "(I)Ljava/lang/Object;", false);
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
    // Instruction analysis helpers
    // =========================================================================

    /**
     * The intended PrefetchPass contract on JVM is a pure declaration-witness
     * marker: {@code INVOKESTATIC dev/topo/Prefetch.accessStreamingWitness ()V}
     * is injected at the start of a matching counted-loop body. The earlier
     * {@code prefetchObjects} / {@code Array.rawData()} injection path was
     * deliberately removed (see PrefetchPass class javadoc), so the tests
     * assert the witness marker rather than the dropped injection.
     */
    private boolean hasPrefetchCall(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode mi) {
                    if (mi.getOpcode() == Opcodes.INVOKESTATIC
                            && "dev/topo/Prefetch".equals(mi.owner)
                            && "accessStreamingWitness".equals(mi.name)
                            && "()V".equals(mi.desc)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int countMethodCalls(byte[] classBytes, String methodName,
                                  String owner, String calledMethod) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode mi) {
                    if (owner.equals(mi.owner) && calledMethod.equals(mi.name)) {
                        count++;
                    }
                }
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
        byte[] input = generateCountedLoopClass();
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        byte[] output = applyPass(input, offConfig(), metadata);

        assertFalse(hasPrefetchCall(output, "process"),
            "Witness marker should not be inserted when mode is off");
    }

    @Test
    void testStreamingLoopGetsPrefetch() {
        byte[] input = generateCountedLoopClass();
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertTrue(hasPrefetchCall(output, "process"),
            "Prefetch.accessStreamingWitness() should be inserted for streaming loop (force mode)");
        // The witness marker takes no arguments and adds no Array.size() call:
        // size() must still appear exactly once, for the loop condition only.
        assertEquals(1, countMethodCalls(output, "process", "dev/topo/Array", "size"),
            "Array.size() should appear once (loop condition only — witness marker is argument-free)");
    }

    @Test
    void testRandomAccessSkipped() {
        byte[] input = generateCountedLoopClass();
        var metadata = buildMetadataWithPattern("app::Processor::process", "random");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertFalse(hasPrefetchCall(output, "process"),
            "Witness marker should not be inserted for random access pattern");
    }

    @Test
    void testNonLoopMethodUnchanged() {
        byte[] input = generateNonLoopClass();
        var metadata = buildMetadataWithPattern("app::Simple::get", "streaming");

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertFalse(hasPrefetchCall(output, "get"),
            "Witness marker should not be inserted in a method without a counted loop");
    }

    @Test
    void testForceModeTransformsUnknownPattern() {
        byte[] input = generateCountedLoopClass();
        // Empty metadata — no access pattern info
        var metadata = new JsonObject();

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertTrue(hasPrefetchCall(output, "process"),
            "Force mode should insert the witness marker even without metadata access pattern");
    }

    @Test
    void testAutoModeWithStreamingPatternIsNoOp() {
        byte[] input = generateCountedLoopClass();
        var metadata = buildMetadataWithPattern("app::Processor::process", "streaming");

        var config = new JsonObject();
        var prefetchCfg = new JsonObject();
        prefetchCfg.addProperty("mode", "auto");
        prefetchCfg.addProperty("distance", 4);
        config.add("prefetchCfg", prefetchCfg);

        byte[] output = applyPass(input, config, metadata);

        // On JVM the HW prefetcher already covers sequential access, so auto
        // mode is a deliberate no-op equivalent to off (PrefetchPass class
        // javadoc + shouldTransform): the witness marker is injected only
        // under force mode. Auto must NOT inject it for a streaming pattern.
        assertFalse(hasPrefetchCall(output, "process"),
            "Auto mode must stay equivalent to off on JVM — no witness marker "
            + "even for a streaming access pattern (HW prefetcher covers it)");
    }

    @Test
    void testAutoModeSkipsUnknownPattern() {
        byte[] input = generateCountedLoopClass();
        // No access pattern in metadata
        var metadata = new JsonObject();

        var config = new JsonObject();
        var prefetchCfg = new JsonObject();
        prefetchCfg.addProperty("mode", "auto");
        config.add("prefetchCfg", prefetchCfg);

        byte[] output = applyPass(input, config, metadata);

        assertFalse(hasPrefetchCall(output, "process"),
            "Auto mode should not insert prefetch without streaming access pattern");
    }
}
