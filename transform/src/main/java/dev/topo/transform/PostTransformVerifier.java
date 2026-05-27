package dev.topo.transform;

import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * F5.2: Post-transform verification for JVM bytecode.
 * Reads transformed .class files and verifies access flags match .topo metadata.
 */
public class PostTransformVerifier {

    private final JsonObject metadata;
    private final JsonObject config;

    public PostTransformVerifier(JsonObject metadata, JsonObject config) {
        this.metadata = metadata;
        this.config = config;
    }

    /**
     * Verify all .class files in the given directory.
     */
    public PostTransformResult verify(Path classDir) throws IOException {
        PostTransformResult result = new PostTransformResult();

        // Collect all .class files
        List<Path> classFiles = new ArrayList<>();
        Files.walkFileTree(classDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".class")) {
                    classFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        JsonObject functions = metadata.has("functions")
            ? metadata.getAsJsonObject("functions") : new JsonObject();

        for (Path classFile : classFiles) {
            verifyClass(classFile, functions, result);
        }

        return result;
    }

    private void verifyClass(Path classFile, JsonObject functions,
                            PostTransformResult result) throws IOException {
        ClassNode cn = new ClassNode();
        try (InputStream is = Files.newInputStream(classFile)) {
            new ClassReader(is).accept(cn, 0);
        }

        String className = cn.name; // e.g., "app/Main"

        for (MethodNode mn : cn.methods) {
            // Skip synthetic methods (bridges, lambda, etc.)
            if ((mn.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
            // Skip constructors and static initializers
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) continue;

            String qualifiedName = className.replace("/", "::") + "::" + mn.name;

            // Check if this method has .topo metadata
            if (!functions.has(qualifiedName)) continue;

            JsonObject fnMeta = functions.getAsJsonObject(qualifiedName);

            // Verify visibility
            if (fnMeta.has("visibility")) {
                String expectedVis = fnMeta.get("visibility").getAsString();
                String actualVis = accessToVisibility(mn.access);

                if (expectedVis.equals(actualVis)) {
                    result.visibilityVerified++;
                } else {
                    result.visibilityMismatch++;
                    result.failures.add(String.format(
                        "visibility mismatch: %s expected=%s actual=%s",
                        qualifiedName, expectedVis, actualVis));
                }
            }

            // Verify const (ACC_FINAL)
            if (fnMeta.has("isConst") && fnMeta.get("isConst").getAsBoolean()) {
                if ((mn.access & Opcodes.ACC_FINAL) == 0) {
                    result.failures.add(String.format(
                        "const mismatch: %s expected ACC_FINAL but not set",
                        qualifiedName));
                }
            }
        }
    }

    private static String accessToVisibility(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) return "public";
        if ((access & Opcodes.ACC_PROTECTED) != 0) return "protected";
        if ((access & Opcodes.ACC_PRIVATE) != 0) return "private";
        return "internal"; // package-private
    }
}
