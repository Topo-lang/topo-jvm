package dev.topo.transform;

import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Maps Topo symbol names to JVM internal names and descriptors.
 *
 * Topo uses C++-style qualified names (e.g., "MyClass::process").
 * JVM uses internal names (e.g., "com/example/MyClass") and method descriptors.
 *
 * When constructed with metadata and a class directory (or ClassNode list),
 * the mapper builds a functionToClass index that resolves namespace-level
 * Topo names (e.g., "app::run") to the correct JVM class (e.g., "app/Main").
 */
public class BytecodeSymbolMapper {

    // Topo qualified name -> JVM internal class name
    private final Map<String, String> functionToClass;

    // Topo type name -> JVM type descriptor
    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
        Map.entry("void", "V"),
        Map.entry("bool", "Z"),
        Map.entry("int", "I"),
        Map.entry("long", "J"),
        Map.entry("float", "F"),
        Map.entry("double", "D"),
        Map.entry("byte", "B"),
        Map.entry("short", "S"),
        Map.entry("char", "C"),
        Map.entry("string", "Ljava/lang/String;"),
        Map.entry("String", "Ljava/lang/String;")
    );

    /** Backward-compatible no-arg constructor (empty functionToClass map). */
    public BytecodeSymbolMapper() {
        this.functionToClass = Map.of();
    }

    /**
     * Construct a mapper with metadata-driven and .class-scan-driven resolution.
     *
     * Pass 1 (metadata): extracts class->memberFunction mappings from metadata "classes".
     * Pass 2 (.class scan): walks classDir for .class files, derives namespace-level keys.
     * Pass 1 entries take priority over Pass 2.
     */
    public BytecodeSymbolMapper(JsonObject metadata, Path classDir) throws IOException {
        this.functionToClass = new HashMap<>();
        populateFromMetadata(metadata);
        populateFromClassScan(loadClassNodes(classDir));
    }

    /**
     * Construct a mapper with metadata-driven and pre-loaded ClassNode resolution.
     * Same two-pass logic but avoids re-reading .class files when nodes are already loaded.
     */
    public BytecodeSymbolMapper(JsonObject metadata, List<ClassNode> classNodes) {
        this.functionToClass = new HashMap<>();
        populateFromMetadata(metadata);
        populateFromClassScan(classNodes);
    }

    /** Pass 1: populate from metadata "classes" entries. */
    private void populateFromMetadata(JsonObject metadata) {
        if (!metadata.has("classes")) return;
        var classes = metadata.getAsJsonObject("classes");
        for (var entry : classes.entrySet()) {
            String topoClassName = entry.getKey(); // e.g. "app::Main"
            String jvmClassName = topoClassName.replace("::", "/"); // e.g. "app/Main"
            var classObj = entry.getValue().getAsJsonObject();
            if (classObj.has("memberFunctions")) {
                for (var memberEl : classObj.getAsJsonArray("memberFunctions")) {
                    String memberName = memberEl.getAsString();
                    String key = topoClassName + "::" + memberName;
                    functionToClass.put(key, jvmClassName);
                }
            }
        }
    }

    /** Pass 2: populate from ClassNode list (namespace-level keys). Only adds if not already present. */
    private void populateFromClassScan(List<ClassNode> classNodes) {
        for (var cn : classNodes) {
            // Derive the namespace path: drop the last '/' segment from the JVM internal name
            // e.g. "app/Main" -> "app", "com/example/Foo" -> "com/example"
            int lastSlash = cn.name.lastIndexOf('/');
            if (lastSlash < 0) continue; // top-level class, no namespace to derive

            String packagePath = cn.name.substring(0, lastSlash); // "app"
            String topoNamespace = packagePath.replace("/", "::"); // "app"

            for (var mn : cn.methods) {
                if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) continue;
                String key = topoNamespace + "::" + mn.name; // "app::init"
                functionToClass.putIfAbsent(key, cn.name);   // Pass 1 takes priority
            }
        }
    }

    /**
     * Load ClassNode list from a directory of .class files.
     *
     * <p>Failures (I/O or {@link IllegalArgumentException} from a corrupt /
     * unsupported-major class file) are reraised as a single
     * {@link IOException} carrying the per-file failure list so the caller
     * cannot silently degrade — the symbol-mapper sits on the same trust
     * boundary as {@link BytecodeVerifier#loadClassNodesTracked} and the
     * same "no silent skip" contract applies. Production code already
     * routes through the {@code (metadata, List&lt;ClassNode&gt;)} ctor in
     * BytecodeVerifier; this directory-based ctor is reserved for the
     * standalone CLI / tests.</p>
     */
    private static List<ClassNode> loadClassNodes(Path classDir) throws IOException {
        var nodes = new ArrayList<ClassNode>();
        var failures = new ArrayList<String>();
        if (!Files.exists(classDir)) return nodes;

        try (var stream = Files.walk(classDir)) {
            stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try (var is = Files.newInputStream(p)) {
                    var reader = new ClassReader(is);
                    var node = new ClassNode();
                    reader.accept(node, 0);
                    nodes.add(node);
                } catch (IOException e) {
                    failures.add(p + ": I/O error: " + e.getMessage());
                } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
                    failures.add(p + ": malformed bytecode: " +
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            });
        }
        if (!failures.isEmpty()) {
            throw new IOException(
                "BytecodeSymbolMapper: " + failures.size() +
                " .class file(s) failed to load: " + String.join("; ", failures));
        }
        return nodes;
    }

    /** Convert Topo qualified name "Namespace::Class::method" to JVM internal class name. */
    public String toJvmClassName(String topoQualifiedName) {
        // Check functionToClass index first (covers metadata and .class scan entries)
        String mapped = functionToClass.get(topoQualifiedName);
        if (mapped != null) return mapped;

        // Strip method name if present (last segment after ::)
        int lastSep = topoQualifiedName.lastIndexOf("::");
        String className = lastSep >= 0 ? topoQualifiedName.substring(0, lastSep) : topoQualifiedName;
        return className.replace("::", "/");
    }

    /** Extract method name from Topo qualified name "Class::method". */
    public String extractMethodName(String topoQualifiedName) {
        int lastSep = topoQualifiedName.lastIndexOf("::");
        return lastSep >= 0 ? topoQualifiedName.substring(lastSep + 2) : topoQualifiedName;
    }

    /** Map a Topo type name to JVM type descriptor. */
    public String toJvmTypeDescriptor(String topoType) {
        if (topoType == null || topoType.isEmpty()) return "V";

        // Check for array types
        if (topoType.endsWith("[]")) {
            return "[" + toJvmTypeDescriptor(topoType.substring(0, topoType.length() - 2));
        }

        // Check primitive/known types
        String mapped = TYPE_MAP.get(topoType);
        if (mapped != null) return mapped;

        // Generic type parameter -> treat as Object
        if (topoType.contains("<")) {
            String rawType = topoType.substring(0, topoType.indexOf('<'));
            return "L" + rawType.replace("::", "/").replace(".", "/") + ";";
        }

        // Object type
        return "L" + topoType.replace("::", "/").replace(".", "/") + ";";
    }

    /** Check if a JVM access flag matches a Topo visibility level. */
    public boolean matchesVisibility(int accessFlags, String topoVisibility) {
        boolean isPublic = (accessFlags & 0x0001) != 0;
        boolean isPrivate = (accessFlags & 0x0002) != 0;
        boolean isProtected = (accessFlags & 0x0004) != 0;

        return switch (topoVisibility) {
            case "public" -> isPublic;
            case "protected" -> isProtected;
            case "private" -> isPrivate;
            case "internal" -> !isPublic && !isProtected && !isPrivate; // package-private
            default -> true; // unknown visibility, pass
        };
    }

    /** Check if a JVM access flag indicates final (maps to Topo const). */
    public boolean isFinal(int accessFlags) {
        return (accessFlags & 0x0010) != 0;
    }
}
