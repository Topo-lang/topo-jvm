package topo.decompile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@code Main.sanitizePath} — guards path-traversal
 * and symlink-escape in the {@code topo-decompile-jvm} subprocess.
 */
class MainPathSanitizeTest {

    private String runSanitizeAndCaptureStderr(String raw, String allowedRoot, String field,
                                                Path[] outPath) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(buf));
            outPath[0] = Main.sanitizePath(raw, allowedRoot, field);
        } finally {
            System.setErr(original);
        }
        return buf.toString();
    }

    @Test
    void rejectsEmptyString() {
        Path[] out = new Path[1];
        String err = runSanitizeAndCaptureStderr("", null, "artifactPath", out);
        assertNull(out[0]);
        assertTrue(err.contains("artifactPath is empty"), err);
    }

    @Test
    void rejectsRelativeParentRefSegment(@TempDir Path tmp) {
        Path root = tmp.resolve("root");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Path[] out = new Path[1];
        String err = runSanitizeAndCaptureStderr("../etc/passwd", root.toString(), "artifactPath", out);
        assertNull(out[0]);
        assertTrue(err.contains("parent-ref"), err);
    }

    @Test
    void rejectsAbsolutePathOutsideAllowedRoot(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("project");
        Files.createDirectories(root);
        Path outside = tmp.resolve("outside.txt");
        Files.writeString(outside, "x");

        Path[] out = new Path[1];
        String err = runSanitizeAndCaptureStderr(outside.toString(), root.toString(),
            "artifactPath", out);
        assertNull(out[0]);
        assertTrue(err.contains("escapes allowedRoot"), err);
    }

    @Test
    void acceptsPathUnderAllowedRoot(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("project");
        Files.createDirectories(root);
        Path artefact = root.resolve("build").resolve("out.jar");
        Files.createDirectories(artefact.getParent());
        Files.writeString(artefact, "x");

        Path[] out = new Path[1];
        String err = runSanitizeAndCaptureStderr(artefact.toString(), root.toString(),
            "artifactPath", out);
        assertNotNull(out[0], "expected canonical path, got null. stderr=" + err);
        // toRealPath on the resolved tmp dir can canonicalise /var → /private/var
        // on macOS; compare end-of-path to dodge the prefix difference.
        assertTrue(out[0].endsWith(root.relativize(artefact)),
            "resolved path should end with the relative artefact path; got " + out[0]);
    }

    @Test
    void acceptsPathWhenNoAllowedRootGiven(@TempDir Path tmp) throws IOException {
        Path artefact = tmp.resolve("out.jar");
        Files.writeString(artefact, "x");

        Path[] out = new Path[1];
        runSanitizeAndCaptureStderr(artefact.toString(), null, "artifactPath", out);
        assertNotNull(out[0]);
    }
}
