package topo.decompile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Entry point for topo-decompile-jvm.
 *
 * Reads a JSON request from stdin, opens the target JAR,
 * lifts .class bytecode to TranspileModel JSON, and writes the result to stdout.
 *
 * Subprocess protocol:
 *   stdin  -> {"artifactPath":"...","metadataPath":"...","level":"direct","functions":[...]}
 *   stdout <- TranspileModule JSON
 *   stderr <- diagnostic messages
 */
public class Main {

    public static void main(String[] args) {
        try {
            int exitCode = run();
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /** Default cap for metadata JSON — 1 MiB; generous for typical topo metadata. */
    static final long METADATA_MAX_BYTES = 1L << 20;

    private static int run() throws Exception {
        // Read JSON request from stdin
        String inputJson;
        try (var reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            inputJson = sb.toString();
        }

        if (inputJson.isBlank()) {
            System.err.println("error: empty request on stdin");
            return 1;
        }

        var gson = new Gson();
        var request = gson.fromJson(inputJson, JsonObject.class);

        // Extract request fields
        String artifactPath = getRequiredString(request, "artifactPath");
        String metadataPath = request.has("metadataPath")
                ? request.get("metadataPath").getAsString() : null;
        String levelStr = request.has("level")
                ? request.get("level").getAsString() : "direct";

        // Optional allowlist root: when --allowed-root <path> appears in
        // the JSON request, every resolved artifact / metadata path MUST
        // live under that root. Threat model: a Topo-as-a-service
        // deployment supplies an allowlist root; local-dev callers omit it
        // and accept that the subprocess inherits the host's trust scope.
        String allowedRoot = request.has("allowedRoot") && !request.get("allowedRoot").isJsonNull()
                ? request.get("allowedRoot").getAsString() : null;

        List<String> functions = new ArrayList<>();
        if (request.has("functions") && request.get("functions").isJsonArray()) {
            for (var elem : request.getAsJsonArray("functions")) {
                functions.add(elem.getAsString());
            }
        }

        // Validate artifact path. canonicalise, reject ".." traversal, and
        // (when allowedRoot is set) require containment under it.
        Path jarPath = sanitizePath(artifactPath, allowedRoot, "artifactPath");
        if (jarPath == null) {
            return 1;
        }
        if (!Files.exists(jarPath)) {
            System.err.println("error: artifact not found: " + artifactPath);
            return 1;
        }

        // Read metadata if available
        JsonObject metadata = new JsonObject();
        if (metadataPath != null && !metadataPath.isEmpty()) {
            Path metaPath = sanitizePath(metadataPath, allowedRoot, "metadataPath");
            if (metaPath == null) {
                return 1;
            }
            if (Files.exists(metaPath)) {
                long size = Files.size(metaPath);
                if (size > METADATA_MAX_BYTES) {
                    System.err.println("error: metadata file exceeds " + METADATA_MAX_BYTES
                            + "-byte cap: " + metadataPath + " (" + size + " bytes)");
                    return 1;
                }
                String metaJson = Files.readString(metaPath, StandardCharsets.UTF_8);
                metadata = gson.fromJson(metaJson, JsonObject.class);
                System.err.println("info: loaded metadata from " + metadataPath);
            } else {
                System.err.println("warning: metadata file not found: " + metadataPath);
            }
        }

        // Perform the lift
        var lifter = new JVMLifter(metadata, functions);
        JsonObject module = lifter.lift(jarPath, levelStr);

        // Write TranspileModule JSON to stdout
        var prettyGson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(prettyGson.toJson(module));

        return 0;
    }

    private static String getRequiredString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return obj.get(key).getAsString();
    }

    /**
     * Canonicalise an external path and (optionally) require it lives
     * under {@code allowedRoot}.
     *
     * Rejects:
     *   - the literal empty string
     *   - any path whose lexically-normalised form still carries a ``..``
     *     segment (only happens if the input tried to climb above its
     *     own root)
     *   - when {@code allowedRoot} is non-null, anything outside that
     *     directory tree (after both sides are realPath-resolved so
     *     symlink escapes are caught)
     *
     * Returns the canonical {@link Path} on success, or {@code null} on
     * rejection — callers must treat {@code null} as a hard reject and
     * never fall back to the verbatim string. Diagnostic is written to
     * stderr in the audited "error:" form.
     */
    static Path sanitizePath(String raw, String allowedRoot, String field) {
        if (raw == null || raw.isEmpty()) {
            System.err.println("error: " + field + " is empty");
            return null;
        }
        Path requested = Path.of(raw);
        Path normalised = requested.normalize();
        for (Path seg : normalised) {
            if (seg.toString().equals("..")) {
                System.err.println("error: " + field + " contains a parent-ref segment: " + raw);
                return null;
            }
        }
        Path resolved;
        try {
            // toRealPath follows symlinks so a symlink to outside the
            // allowedRoot is caught by the containment check below.
            // NOFOLLOW would let an attacker plant a symlink inside an
            // allowed dir that points at /etc/shadow.
            resolved = Files.exists(normalised)
                    ? normalised.toRealPath()
                    : normalised.toAbsolutePath().normalize();
        } catch (IOException e) {
            System.err.println("error: cannot resolve " + field + " (" + raw + "): " + e.getMessage());
            return null;
        }
        if (allowedRoot != null && !allowedRoot.isEmpty()) {
            Path rootResolved;
            try {
                rootResolved = Path.of(allowedRoot).toRealPath();
            } catch (IOException e) {
                System.err.println("error: cannot resolve allowedRoot (" + allowedRoot + "): " + e.getMessage());
                return null;
            }
            if (!resolved.startsWith(rootResolved)) {
                System.err.println("error: " + field + " escapes allowedRoot: " + raw
                        + " resolves to " + resolved + ", outside " + rootResolved);
                return null;
            }
        }
        return resolved;
    }
}
