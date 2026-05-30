package topo.decompile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JVM lambda recovery.
 *
 * <p>These tests compile real Java source with the JDK compiler so the
 * bytecode contains genuine {@code java.lang.invoke.LambdaMetafactory} and
 * {@code makeConcatWithConstants} invokedynamic instructions (synthesizing
 * those by hand would not faithfully exercise the bootstrap shapes). The
 * resulting classes are packed into a temp JAR and run through the full
 * {@link JVMLifter#lift} path, exercising lambda recovery, the DEDUP
 * suppression of the synthetic {@code lambda$} body, and the conservative
 * fallback for non-lambda bootstraps.
 */
class LambdaRecoveryTest {

    // -----------------------------------------------------------------------
    // (a) Simple no-capture lambda -> lambda node, no INVOKEDYNAMIC unsupported
    // -----------------------------------------------------------------------

    @Test
    void noCaptureLambdaRecovered() throws Exception {
        String src =
                "public class NoCap {\n" +
                "  static int sideEffect() { return 7; }\n" +
                "  public static Runnable make() {\n" +
                "    Runnable r = () -> sideEffect();\n" +
                "    return r;\n" +
                "  }\n" +
                "}\n";

        JsonObject module = liftSource("NoCap", src);

        JsonObject make = findFunction(module, "make");
        assertNotNull(make, "make() should be lifted");

        JsonObject lambda = findExprByKind(make.getAsJsonArray("body"), "lambda");
        assertNotNull(lambda, "make() body should contain a recovered lambda node");
        assertEquals(0, lambda.getAsJsonArray("captures").size(),
                "no-capture lambda has zero captures");
        assertFalse(lambda.getAsJsonArray("body").isEmpty(),
                "lambda body should be linked from the synthetic impl method");

        // The producing method must no longer be flagged unsupported for indy.
        assertFalse(hasUnsupported(make, "INVOKEDYNAMIC"),
                "recovered lambda must clear the INVOKEDYNAMIC unsupported marker");

        // DEDUP: no orphan synthetic lambda$ standalone function.
        assertNull(findFunctionNameContains(module, "lambda$"),
                "synthetic lambda$ body must be suppressed (no orphan function)");
    }

    // -----------------------------------------------------------------------
    // (b) Capturing lambda -> lambda node with a capture entry for x
    // -----------------------------------------------------------------------

    @Test
    void capturingLambdaRecovered() throws Exception {
        String src =
                "import java.util.function.Supplier;\n" +
                "public class Cap {\n" +
                "  public static Supplier<Integer> make(int x) {\n" +
                "    Supplier<Integer> s = () -> x;\n" +
                "    return s;\n" +
                "  }\n" +
                "}\n";

        JsonObject module = liftSource("Cap", src);

        JsonObject make = findFunction(module, "make");
        assertNotNull(make, "make() should be lifted");

        JsonObject lambda = findExprByKind(make.getAsJsonArray("body"), "lambda");
        assertNotNull(lambda, "make() body should contain a recovered lambda node");

        JsonArray captures = lambda.getAsJsonArray("captures");
        assertEquals(1, captures.size(), "capturing lambda has exactly one capture (x)");
        JsonObject cap = captures.get(0).getAsJsonObject();
        assertEquals("by_value", cap.get("mode").getAsString(),
                "JVM lambda capture is by-value");
        assertNotNull(cap.get("name"), "capture entry must be named");

        assertFalse(hasUnsupported(make, "INVOKEDYNAMIC"),
                "recovered capturing lambda must clear the INVOKEDYNAMIC marker");
        assertNull(findFunctionNameContains(module, "lambda$"),
                "synthetic lambda$ body must be suppressed (no orphan function)");
    }

    // -----------------------------------------------------------------------
    // (c) Non-lambda indy (string concat) stays conservatively unsupported
    // -----------------------------------------------------------------------

    @Test
    void stringConcatStaysUnsupported() throws Exception {
        // "a" + i compiles to an invokedynamic backed by
        // java/lang/invoke/StringConcatFactory.makeConcatWithConstants —
        // NOT LambdaMetafactory. Must remain unsupported, no bogus lambda.
        String src =
                "public class Concat {\n" +
                "  public static String make(int i) {\n" +
                "    return \"a\" + i;\n" +
                "  }\n" +
                "}\n";

        JsonObject module = liftSource("Concat", src);

        JsonObject make = findFunction(module, "make");
        assertNotNull(make, "make() should be lifted");

        assertNull(findExprByKind(make.getAsJsonArray("body"), "lambda"),
                "string-concat indy must NOT be misrecovered as a lambda");
        assertTrue(hasUnsupported(make, "INVOKEDYNAMIC"),
                "non-LambdaMetafactory bootstrap must stay conservatively unsupported");
    }

    // -----------------------------------------------------------------------
    // Helpers: compile source -> JAR -> lift
    // -----------------------------------------------------------------------

    private JsonObject liftSource(String className, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK system Java compiler must be available "
                + "(toolchain must be a JDK, not a JRE)");

        Path tmp = Files.createTempDirectory("lambda-recovery");
        Path srcDir = Files.createDirectories(tmp.resolve("src"));
        Path outDir = Files.createDirectories(tmp.resolve("out"));
        Path srcFile = srcDir.resolve(className + ".java");
        Files.writeString(srcFile, source);

        int rc = compiler.run(null, null, null,
                "-d", outDir.toString(), srcFile.toString());
        assertEquals(0, rc, "javac must compile the test source successfully");

        Path jar = tmp.resolve(className + ".jar");
        try (var jos = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(outDir)) {
            List<Path> classes = new ArrayList<>();
            walk.filter(p -> p.toString().endsWith(".class")).forEach(classes::add);
            for (Path c : classes) {
                String entry = outDir.relativize(c).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entry));
                jos.write(Files.readAllBytes(c));
                jos.closeEntry();
            }
        }

        var lifter = new JVMLifter(new JsonObject(), Collections.emptyList());
        return lifter.lift(jar, "structured");
    }

    private static JsonObject findFunction(JsonObject module, String simpleName) {
        for (var f : module.getAsJsonArray("functions")) {
            var fo = f.getAsJsonObject();
            String qn = fo.get("qualifiedName").getAsString();
            if (qn.equals(simpleName) || qn.endsWith("." + simpleName)) return fo;
        }
        return null;
    }

    private static String findFunctionNameContains(JsonObject module, String needle) {
        for (var f : module.getAsJsonArray("functions")) {
            String qn = f.getAsJsonObject().get("qualifiedName").getAsString();
            if (qn.contains(needle)) return qn;
        }
        return null;
    }

    private static boolean hasUnsupported(JsonObject func, String tag) {
        if (!func.has("unsupported")) return false;
        for (var u : func.getAsJsonArray("unsupported")) {
            if (tag.equals(u.getAsString())) return true;
        }
        return false;
    }

    /** Recursively search a statement array for an expression of the given kind. */
    private static JsonObject findExprByKind(JsonArray nodes, String kind) {
        if (nodes == null) return null;
        for (var el : nodes) {
            if (!el.isJsonObject()) continue;
            JsonObject found = scan(el.getAsJsonObject(), kind);
            if (found != null) return found;
        }
        return null;
    }

    private static JsonObject scan(JsonObject node, String kind) {
        if (node.has("kind") && kind.equals(node.get("kind").getAsString())) {
            return node;
        }
        for (var e : node.entrySet()) {
            var v = e.getValue();
            if (v.isJsonObject()) {
                JsonObject f = scan(v.getAsJsonObject(), kind);
                if (f != null) return f;
            } else if (v.isJsonArray()) {
                for (var el : v.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        JsonObject f = scan(el.getAsJsonObject(), kind);
                        if (f != null) return f;
                    }
                }
            }
        }
        return null;
    }
}
