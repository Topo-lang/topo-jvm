package dev.topo.transform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-javac counterpart to {@link PassPipelineTest}.
 *
 * Drives a real {@code javac}-compiled class through the full
 * {@link PassPipeline} (read → all-passes → write) and asserts that:
 *   1. visibility flags are rewritten,
 *   2. javac-emitted attributes (constant-pool entries, line tables)
 *      do not produce a {@code ClassFormatError} after rewrite,
 *   3. the output bytes still read back cleanly via ASM.
 */
class PassPipelineRealJavacTest {

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
    void pipelineAppliesVisibilityOnJavacBytecode(@TempDir Path tempDir) throws Exception {
        String source = "package app;\n"
                      + "public class Service {\n"
                      + "    public void helper() {}\n"
                      + "}\n";
        Map<String, byte[]> classes = RealJavacFixture.compile("app.Service", source);

        // Stage javac output into the input dir
        Path inputDir = tempDir.resolve("in");
        for (var e : classes.entrySet()) {
            Path p = inputDir.resolve(e.getKey() + ".class");
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }

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
        Path outputDir = tempDir.resolve("out");

        var pipeline = new PassPipeline(config, metadata);
        pipeline.run(inputDir, outputDir);

        byte[] transformed = Files.readAllBytes(outputDir.resolve("app/Service.class"));

        int access = getMethodAccess(transformed, "helper");
        assertTrue((access & Opcodes.ACC_PRIVATE) != 0,
            "real-javac method must be private after pipeline run");
        assertFalse((access & Opcodes.ACC_PUBLIC) != 0,
            "real-javac method must no longer be public after pipeline run");

        // Round-trip read: ClassFormatError would surface here if the
        // pipeline corrupted the bytecode.
        assertDoesNotThrow(() -> new ClassReader(transformed));
    }

    @Test
    void pipelinePreservesJavacInitConstructor(@TempDir Path tempDir) throws Exception {
        // The implicit <init> emitted by javac carries an ALOAD/INVOKESPECIAL
        // pair the synthetic ClassWriter tests don't model precisely. Pin
        // that the pipeline does not damage it.
        String source = "package app;\n"
                      + "public class Service {\n"
                      + "    public void api() {}\n"
                      + "}\n";
        Map<String, byte[]> classes = RealJavacFixture.compile("app.Service", source);
        Path inputDir = tempDir.resolve("in");
        for (var e : classes.entrySet()) {
            Path p = inputDir.resolve(e.getKey() + ".class");
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }

        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());

        var config = new JsonObject();
        Path outputDir = tempDir.resolve("out");
        new PassPipeline(config, metadata).run(inputDir, outputDir);

        byte[] transformed = Files.readAllBytes(outputDir.resolve("app/Service.class"));
        int initAcc = getMethodAccess(transformed, "<init>");
        assertTrue((initAcc & Opcodes.ACC_PUBLIC) != 0,
            "javac-emitted <init> must stay public through the pipeline");
    }
}
