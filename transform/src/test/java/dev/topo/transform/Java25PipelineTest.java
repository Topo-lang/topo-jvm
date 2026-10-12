package dev.topo.transform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises class major 69 and Java 25's finalized flexible constructor body. */
class Java25PipelineTest {
    @Test
    void verifiesTransformsAndExecutesJava25(@TempDir Path tempDir) throws Exception {
        var classes = RealJavacFixture.compile("app.Java25", """
            package app;
            public class Java25 {
                private final int value;
                public Java25(int input) {
                    if (input < 0) throw new IllegalArgumentException("negative");
                    super();
                    value = input;
                }
                public int answer() { return value + 2; }
            }
            """);
        Path input = tempDir.resolve("in");
        for (var entry : classes.entrySet()) {
            assertEquals(69, new ClassReader(entry.getValue()).readUnsignedShort(6));
            Path file = input.resolve(entry.getKey() + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        var function = new JsonObject();
        function.addProperty("qualifiedName", "app::Java25::answer");
        function.addProperty("simpleName", "answer");
        function.addProperty("visibility", "public");
        function.addProperty("returnType", "int");
        function.add("params", new JsonArray());
        var functions = new JsonObject();
        functions.add("app::Java25::answer", function);
        var metadata = new JsonObject();
        metadata.add("functions", functions);
        var config = new JsonObject();

        assertTrue(new BytecodeVerifier().verify(input, metadata).passed());
        Path output = tempDir.resolve("out");
        new PassPipeline(config, metadata).run(input, output);
        var verification = new PostTransformVerifier(metadata, config).verify(output);
        assertTrue(verification.passed());
        assertEquals(1, verification.visibilityVerified);
        assertEquals(69, new ClassReader(Files.readAllBytes(
            output.resolve("app/Java25.class"))).readUnsignedShort(6));

        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
            var type = loader.loadClass("app.Java25");
            var instance = type.getConstructor(int.class).newInstance(40);
            assertEquals(42, type.getMethod("answer").invoke(instance));
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> type.getConstructor(int.class).newInstance(-1));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }
    }
}
