package dev.topo.transform;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.topo.transform.pass.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Main entry point for topo-transform.jar.
 * Modes:
 *   --verify       : validate .class files against topo metadata
 *   --transform    : apply ASM passes to .class files
 *   --post-verify  : verify transformed .class files still match .topo declarations
 */
public class TopoTransformer {
    public static void main(String[] args) throws Exception {
        // Parse args
        String mode = null;
        String classDir = null;
        String metadataPath = null;
        String configPath = null;
        String outputDir = null;
        String sidecarDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--verify" -> mode = "verify";
                case "--transform" -> mode = "transform";
                case "--post-verify" -> mode = "post-verify";
                case "--classes" -> classDir = args[++i];
                case "--metadata" -> metadataPath = args[++i];
                case "--config" -> configPath = args[++i];
                case "--output" -> outputDir = args[++i];
                case "--sidecar-dir" -> sidecarDir = args[++i];
            }
        }

        if (mode == null) {
            System.err.println("Usage: topo-transform --verify|--transform|--post-verify [options]");
            System.exit(1);
        }

        var gson = new Gson();

        if ("verify".equals(mode)) {
            if (classDir == null || metadataPath == null) {
                System.err.println("--verify requires --classes and --metadata");
                System.exit(1);
            }
            String metadataJson = Files.readString(Path.of(metadataPath));
            var metadata = gson.fromJson(metadataJson, JsonObject.class);

            var verifier = new BytecodeVerifier();
            var result = verifier.verify(Path.of(classDir), metadata);

            // Output result as JSON to stdout
            System.out.println(gson.toJson(result));
            System.exit(result.passed() ? 0 : 1);

        } else if ("transform".equals(mode)) {
            if (classDir == null || configPath == null || outputDir == null) {
                System.err.println("--transform requires --classes, --config, and --output");
                System.exit(1);
            }
            String configJson = Files.readString(Path.of(configPath));
            var config = gson.fromJson(configJson, JsonObject.class);
            String metaJson = metadataPath != null ? Files.readString(Path.of(metadataPath)) : "{}";
            var metadata = gson.fromJson(metaJson, JsonObject.class);

            var transformer = new PassPipeline(config, metadata);
            transformer.run(Path.of(classDir), Path.of(outputDir),
                            sidecarDir != null ? Path.of(sidecarDir) : null);

        } else if ("post-verify".equals(mode)) {
            if (classDir == null || metadataPath == null || configPath == null) {
                System.err.println("--post-verify requires --classes, --metadata, and --config");
                System.exit(1);
            }
            String metaJson = Files.readString(Path.of(metadataPath));
            var metadata = gson.fromJson(metaJson, JsonObject.class);
            String cfgJson = Files.readString(Path.of(configPath));
            var config = gson.fromJson(cfgJson, JsonObject.class);

            var verifier = new PostTransformVerifier(metadata, config);
            var result = verifier.verify(Path.of(classDir));

            // Output JSON result
            JsonObject output = new JsonObject();
            output.addProperty("passed", result.passed());
            output.addProperty("visibilityVerified", result.visibilityVerified);
            output.addProperty("visibilityMismatch", result.visibilityMismatch);
            output.addProperty("totalChecks", result.totalChecks());

            JsonArray failures = new JsonArray();
            for (String f : result.failures) failures.add(f);
            output.add("failures", failures);

            System.out.println(gson.toJson(output));
            System.exit(result.passed() ? 0 : 1);
        }
    }
}
