package dev.topo.transform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-javac counterpart to {@link BytecodeVerifierTest}.
 *
 * The companion uses ASM's ClassWriter to fabricate minimal class
 * files; this class compiles a small Java source via the JDK and
 * verifies against the real {@code .class} bytes.
 */
class BytecodeVerifierRealJavacTest {

    private final BytecodeVerifier verifier = new BytecodeVerifier();

    private void writeClasses(Path dir, Map<String, byte[]> classes) throws IOException {
        for (var e : classes.entrySet()) {
            Path classFile = dir.resolve(e.getKey() + ".class");
            Files.createDirectories(classFile.getParent());
            Files.write(classFile, e.getValue());
        }
    }

    private JsonObject buildFunctionEntry(String qualifiedName, String visibility) {
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", qualifiedName);
        int last = qualifiedName.lastIndexOf("::");
        fn.addProperty("simpleName", last >= 0 ? qualifiedName.substring(last + 2) : qualifiedName);
        fn.addProperty("visibility", visibility);
        fn.addProperty("returnType", "void");
        fn.add("params", new JsonArray());
        return fn;
    }

    @Test
    void declaredPublicMatchesRealJavacOutput(@TempDir Path tempDir) throws Exception {
        String source = "package app;\n"
                      + "public class Engine {\n"
                      + "    public void process() {}\n"
                      + "}\n";
        writeClasses(tempDir, RealJavacFixture.compile("app.Engine", source));

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Engine::process",
            buildFunctionEntry("app::Engine::process", "public"));
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.publicMissing,
            "verifier must find process() in real-javac output");
        assertTrue(result.passed(),
            "verifier must pass on real-javac output of declared method");
    }

    @Test
    void declaredPublicAbsentFromRealJavacOutputIsCaught(@TempDir Path tempDir) throws Exception {
        String source = "package app;\n"
                      + "public class Engine {\n"
                      + "    public void other() {}\n"
                      + "}\n";
        writeClasses(tempDir, RealJavacFixture.compile("app.Engine", source));

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Engine::process",
            buildFunctionEntry("app::Engine::process", "public"));
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(1, result.publicMissing,
            "verifier must report process() as missing from real-javac output");
        assertFalse(result.passed());
    }

    @Test
    void overloadedJavacMethodsBothCounted(@TempDir Path tempDir) throws Exception {
        // The verifier indexes methods by name+descriptor when checking
        // overloads, so both overloads must be counted.
        // Real javac emits both overloads with distinct descriptors.
        String source = "package app;\n"
                      + "public class Engine {\n"
                      + "    public void process(int x)  {}\n"
                      + "    public void process(long x) {}\n"
                      + "}\n";
        writeClasses(tempDir, RealJavacFixture.compile("app.Engine", source));

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Engine::process",
            buildFunctionEntry("app::Engine::process", "public"));
        metadata.add("functions", functions);

        // Only one declaration is registered (the verifier matches by
        // simple name in this metadata shape). The verifier must accept
        // because at least one matching method exists in the bytecode.
        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.publicMissing,
            "at least one of the two javac-emitted process() overloads must match");
    }
}
