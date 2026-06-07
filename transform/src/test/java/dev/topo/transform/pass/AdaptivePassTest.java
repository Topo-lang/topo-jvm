package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdaptivePass} — focuses on the counter-field-per-method
 * invariant. The counter field name is derived from the method name only and
 * is shared by every overload of that name; the pass must emit the field
 * exactly once even when two overloads both qualify, otherwise the class has
 * two identically-named fields and fails to load (ClassFormatError).
 */
class AdaptivePassTest {

    private byte[] applyPass(byte[] input, JsonObject config, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new AdaptivePass(config, metadata).createVisitor(writer);
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    /** Force-mode adaptive config so all stage-mapped functions are adapted. */
    private JsonObject forceConfig() {
        var config = new JsonObject();
        var adaptiveCfg = new JsonObject();
        adaptiveCfg.addProperty("mode", "force");
        config.add("adaptiveCfg", adaptiveCfg);
        return config;
    }

    /** Metadata that places the named function in a logic block (stage-mapped). */
    private JsonObject stageMetadata(String namespace, String... functions) {
        var metadata = new JsonObject();
        var logicBlocks = new JsonObject();
        var block = new JsonObject();
        block.addProperty("qualifiedName", namespace + "::run");
        var calledFunctions = new JsonArray();
        var stages = new JsonArray();
        int stage = 0;
        for (String fn : functions) {
            calledFunctions.add(fn);
            stages.add(stage++);
        }
        block.add("calledFunctions", calledFunctions);
        block.add("stages", stages);
        logicBlocks.add("block_0", block);
        metadata.add("logicBlocks", logicBlocks);
        return metadata;
    }

    /**
     * Class with two overloads of {@code process}: {@code process(I)V} and
     * {@code process(J)V}. Both qualify for adaptation, so both derive the same
     * method-name-only counter field.
     */
    private byte[] generateOverloadedClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Worker", null,
                "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        var m1 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "process", "(I)V", null, null);
        m1.visitCode();
        m1.visitInsn(Opcodes.RETURN);
        m1.visitMaxs(0, 1);
        m1.visitEnd();

        var m2 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "process", "(J)V", null, null);
        m2.visitCode();
        m2.visitInsn(Opcodes.RETURN);
        m2.visitMaxs(0, 2);
        m2.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private long countCounterFields(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        return cn.fields.stream()
            .filter(f -> f.name.startsWith("__topo_adaptive_hits_"))
            .count();
    }

    @Test
    void overloadsEmitSingleCounterField() {
        byte[] input = generateOverloadedClass();
        // Both process overloads are stage-mapped under namespace app.
        var metadata = stageMetadata("app", "process");
        byte[] output = applyPass(input, forceConfig(), metadata);

        assertEquals(1, countCounterFields(output),
            "Two overloads sharing a method-name-only counter field must emit "
            + "the field exactly once — a duplicate field is a ClassFormatError");

        // The class must remain loadable (no duplicate field).
        ClassLoader cl = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if ("app.Worker".equals(name)) {
                    return defineClass(name, output, 0, output.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        assertDoesNotThrow(() -> Class.forName("app.Worker", false, cl),
            "Adapted class with overloaded methods must load without "
            + "ClassFormatError");
    }
}
