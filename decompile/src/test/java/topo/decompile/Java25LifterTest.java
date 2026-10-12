package topo.decompile;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class Java25LifterTest {
    @Test
    void liftsRealMajor69ClassAndKeepsFunctionBody(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("Java25.java");
        Files.writeString(source, """
            public class Java25 {
                public Java25(int value) {
                    if (value < 0) throw new IllegalArgumentException();
                    super();
                }
                public static int answer() { return 42; }
            }
            """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null,
            "--release", "25", "-d", dir.toString(), source.toString()));
        byte[] bytes = Files.readAllBytes(dir.resolve("Java25.class"));
        assertEquals(69, new ClassReader(bytes).readUnsignedShort(6));
        Path jar = dir.resolve("java25.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("Java25.class"));
            out.write(bytes);
            out.closeEntry();
        }
        var lifter = new JVMLifter(new JsonObject(), List.of("Java25.answer"));
        var module = lifter.lift(jar, "structured");
        var functions = module.getAsJsonArray("functions");
        assertEquals(1, functions.size());
        var answer = functions.get(0).getAsJsonObject();
        assertEquals("Java25.answer", answer.get("qualifiedName").getAsString());
        assertFalse(answer.getAsJsonArray("body").isEmpty());
        assertTrue(answer.toString().contains("42"));
        assertTrue(!answer.has("unsupported") || answer.getAsJsonArray("unsupported").isEmpty());
    }
}
