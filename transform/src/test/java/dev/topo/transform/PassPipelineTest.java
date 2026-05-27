package dev.topo.transform;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PassPipeline — verifies end-to-end pass chain execution.
 */
class PassPipelineTest {

    private byte[] generateClass(String className, String methodName, int access) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null,
                 "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        var mv = cw.visitMethod(access, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private int getMethodAccess(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        int[] result = {-1};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (name.equals(methodName)) result[0] = access;
                return null;
            }
        }, 0);
        return result[0];
    }

    @Test
    void pipelineAppliesVisibility(@TempDir Path tempDir) throws IOException {
        // Write a class with a public method
        byte[] cls = generateClass("app/Service", "helper", Opcodes.ACC_PUBLIC);
        Path classFile = tempDir.resolve("app/Service.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, cls);

        // Metadata: mark helper as private (Object format keyed by qualifiedName)
        var metadata = new JsonObject();
        var functions = new JsonObject();
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", "app::Service::helper");
        fn.addProperty("simpleName", "helper");
        fn.addProperty("visibility", "private");
        fn.addProperty("returnType", "void");
        fn.add("params", new JsonArray());
        functions.add("app::Service::helper", fn);
        metadata.add("functions", functions);

        var config = new JsonObject();
        Path outputDir = tempDir.resolve("output");

        var pipeline = new PassPipeline(config, metadata);
        pipeline.run(tempDir.resolve("app").getParent(), outputDir);

        // Verify output
        byte[] transformed = Files.readAllBytes(outputDir.resolve("app/Service.class"));
        int access = getMethodAccess(transformed, "helper");

        assertTrue((access & Opcodes.ACC_PRIVATE) != 0, "method should be private after pipeline");
        assertFalse((access & Opcodes.ACC_PUBLIC) != 0, "method should not be public after pipeline");
    }

    @Test
    void pipelinePreservesClassStructure(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClass("app/Main", "run", Opcodes.ACC_PUBLIC);
        Path classFile = tempDir.resolve("app/Main.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, cls);

        var metadata = new JsonObject();
        var config = new JsonObject();
        Path outputDir = tempDir.resolve("output");

        var pipeline = new PassPipeline(config, metadata);
        pipeline.run(tempDir.resolve("app").getParent(), outputDir);

        assertTrue(Files.exists(outputDir.resolve("app/Main.class")),
            "output should preserve directory structure");
    }

    @Test
    void emptyDirectoryProducesEmptyOutput(@TempDir Path tempDir) throws IOException {
        Path inputDir = tempDir.resolve("input");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(inputDir);

        var pipeline = new PassPipeline(new JsonObject(), new JsonObject());
        pipeline.run(inputDir, outputDir);

        assertTrue(Files.exists(outputDir), "output directory should be created");
    }
}
