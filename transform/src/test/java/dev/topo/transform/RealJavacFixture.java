package dev.topo.transform;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper for running real {@code javac} from inside the pass test suite.
 *
 * The base pass tests (VisibilityPassTest, ObfuscationPassTest, ...)
 * fabricate bytecode via ASM's {@code ClassWriter} — fast, but the
 * synthetic shape can mask differences against real {@code javac}
 * output (no StackMapFrame intricacies, no synthetic accessors, no
 * lambda{@code $0} bodies, etc.). This helper lets sibling
 * {@code *RealJavacTest} classes drive the same passes against real
 * {@code javac} output so the test suite has both layers.
 *
 * Issue: {@code jvm-pass-tests-rely-on-synthetic-bytecode-not-javac}.
 */
public final class RealJavacFixture {
    private RealJavacFixture() {}

    /**
     * Compile a single Java source string. The class name must match
     * the {@code public class <name>} declaration inside the source.
     * Returns a map from internal class name (e.g. {@code app/Service})
     * to compiled bytecode.
     */
    public static Map<String, byte[]> compile(String className, String source)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "No system Java compiler available; the JDK is required for "
              + "RealJavacFixture (the runtime JRE alone does not bundle javac). "
              + "Ensure tests run under a JDK, not just a JRE.");
        }

        Path tempDir = Files.createTempDirectory("topo-realjavac-");
        try {
            JavaFileObject file = new InMemoryJavaSource(className, source);

            try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
                fm.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT,
                    List.of(tempDir.toFile()));
                JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, null, null, null, Collections.singletonList(file));
                if (!task.call()) {
                    throw new IllegalStateException(
                        "javac failed to compile in-memory source for " + className);
                }
            }

            Map<String, byte[]> result = new HashMap<>();
            try (var paths = Files.walk(tempDir)) {
                paths.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    try {
                        byte[] bytes = Files.readAllBytes(p);
                        // internal name = relative path minus .class, slash-separated
                        Path rel = tempDir.relativize(p);
                        String internal = rel.toString().replace(java.io.File.separatorChar, '/');
                        internal = internal.substring(0, internal.length() - ".class".length());
                        result.put(internal, bytes);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            return result;
        } finally {
            // Best-effort cleanup
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                });
            } catch (IOException ignore) {}
        }
    }

    /**
     * Compile multiple Java sources in a single javac invocation so they
     * can reference each other (e.g. {@code class B extends A}).
     *
     * <p>Keys of {@code sourcesByClassName} are fully-qualified names
     * (dot-separated); values are the corresponding source text. Returns
     * a map keyed by internal name (slash-separated) — the same shape
     * {@link #compile(String, String)} returns.</p>
     */
    public static Map<String, byte[]> compileAll(Map<String, String> sourcesByClassName)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "No system Java compiler available; the JDK is required for "
              + "RealJavacFixture (the runtime JRE alone does not bundle javac).");
        }

        Path tempDir = Files.createTempDirectory("topo-realjavac-");
        try {
            List<JavaFileObject> files = new ArrayList<>(sourcesByClassName.size());
            for (var entry : sourcesByClassName.entrySet()) {
                files.add(new InMemoryJavaSource(entry.getKey(), entry.getValue()));
            }

            try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
                fm.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT,
                    List.of(tempDir.toFile()));
                JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, null, null, null, files);
                if (!task.call()) {
                    throw new IllegalStateException(
                        "javac failed to compile in-memory sources for "
                      + sourcesByClassName.keySet());
                }
            }

            Map<String, byte[]> result = new HashMap<>();
            try (var paths = Files.walk(tempDir)) {
                paths.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    try {
                        byte[] bytes = Files.readAllBytes(p);
                        Path rel = tempDir.relativize(p);
                        String internal = rel.toString().replace(java.io.File.separatorChar, '/');
                        internal = internal.substring(0, internal.length() - ".class".length());
                        result.put(internal, bytes);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            return result;
        } finally {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                });
            } catch (IOException ignore) {}
        }
    }

    /** Compile and return the bytecode for the single named class. */
    public static byte[] compileOne(String className, String source) throws IOException {
        Map<String, byte[]> all = compile(className, source);
        byte[] bytes = all.get(className.replace('.', '/'));
        if (bytes == null) {
            throw new IllegalStateException(
                "no .class output for " + className + " (outputs: " + all.keySet() + ")");
        }
        return bytes;
    }

    private static final class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String code;
        InMemoryJavaSource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                  Kind.SOURCE);
            this.code = code;
        }
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
