package topo.decompile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Core lifter: reads .class files from a JAR, converts bytecode to TranspileModel JSON.
 *
 * Uses ASM tree API for structured access to class/method/instruction data.
 * When metadata is available, uses it to recover original names and type information
 * that may have been lost during obfuscation or compilation.
 */
public class JVMLifter {

    private final JsonObject metadata;
    private final Set<String> requestedFunctions;

    // Obfuscation reverse map: obfuscated name -> original name (from metadata)
    private final Map<String, String> reverseNameMap;

    /**
     * @param metadata         topo-metadata.json content (may be empty JsonObject)
     * @param requestedFunctions function names to lift; empty means lift all
     */
    public JVMLifter(JsonObject metadata, List<String> requestedFunctions) {
        this.metadata = metadata != null ? metadata : new JsonObject();
        this.requestedFunctions = new HashSet<>(requestedFunctions);
        this.reverseNameMap = buildReverseNameMap(this.metadata);
    }

    /**
     * Lift a JAR artifact to TranspileModule JSON.
     *
     * @param jarPath path to the JAR file
     * @param level   decompile level: "direct", "structured", "idiomatic"
     * @return TranspileModule JSON object with "types" and "functions" arrays
     */
    public JsonObject lift(Path jarPath, String level) throws IOException {
        var types = new JsonArray();
        var functions = new JsonArray();

        List<ClassNode> classNodes = loadClassNodes(jarPath);

        for (var classNode : classNodes) {
            // Skip synthetic/bridge classes that are compiler artifacts
            if (isSyntheticClass(classNode)) continue;

            // Track the class so collectUnsupported can resolve lambda
            // implementation handles against its method table.
            currentClassNode = classNode;

            // Lift class-level type information (fields)
            JsonObject typeDecl = liftClassType(classNode);
            if (typeDecl != null) {
                types.add(typeDecl);
            }

            // DEDUP: collect synthetic lambda-body methods that a recovered
            // LambdaMetafactory invokedynamic now embeds inline as a `lambda`
            // node. They must NOT also be emitted as standalone module
            // functions, otherwise the model carries an orphan
            // `lambda$foo$0` function with no caller.
            Set<String> inlinedLambdaImpls = collectInlinedLambdaImpls(classNode);

            // Lift methods
            for (var methodNode : classNode.methods) {
                // Skip constructors and class initializers for method lifting
                if (methodNode.name.equals("<clinit>")) continue;

                // Skip synthetic lambda bodies already inlined into a lambda node
                if (inlinedLambdaImpls.contains(methodNode.name + methodNode.desc)) {
                    continue;
                }

                String qualifiedName = buildQualifiedName(classNode, methodNode);

                // Filter by requested functions (if any specified)
                if (!requestedFunctions.isEmpty() && !matchesRequest(qualifiedName)) {
                    continue;
                }

                JsonObject func = liftMethod(classNode, methodNode, qualifiedName, level);
                functions.add(func);
            }
        }

        var module = new JsonObject();
        module.add("types", types);
        module.add("functions", functions);
        return module;
    }

    // -----------------------------------------------------------------------
    // JAR / ClassNode loading
    // -----------------------------------------------------------------------

    private List<ClassNode> loadClassNodes(Path jarPath) throws IOException {
        var nodes = new ArrayList<ClassNode>();

        try (var jarFile = new JarFile(jarPath.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;

                // Skip SDK runtime classes (dev/topo/runtime/...)
                if (entry.getName().startsWith("dev/topo/runtime/")) continue;
                // Skip module-info
                if (entry.getName().equals("module-info.class")) continue;

                try (InputStream is = jarFile.getInputStream(entry)) {
                    var reader = new ClassReader(is);
                    var node = new ClassNode();
                    reader.accept(node, ClassReader.EXPAND_FRAMES);
                    nodes.add(node);
                } catch (Exception e) {
                    System.err.println("warning: cannot read class entry "
                            + entry.getName() + ": " + e.getMessage());
                }
            }
        }

        System.err.println("info: loaded " + nodes.size() + " class(es) from "
                + jarPath.getFileName());
        return nodes;
    }

    // -----------------------------------------------------------------------
    // Type lifting (class -> TranspileType)
    // -----------------------------------------------------------------------

    private JsonObject liftClassType(ClassNode classNode) {
        // Only lift classes that have fields worth representing
        if (classNode.fields == null || classNode.fields.isEmpty()) return null;

        var typeDecl = new JsonObject();
        typeDecl.addProperty("qualifiedName", jvmToQualified(classNode.name));
        typeDecl.addProperty("fidelity", "recovered");

        var fields = new JsonArray();
        for (var fieldNode : classNode.fields) {
            // Skip synthetic fields
            if ((fieldNode.access & 0x1000) != 0) continue;

            var field = new JsonObject();
            field.add("type", descriptorToTypeNode(fieldNode.desc));
            field.addProperty("name", resolveOriginalName(fieldNode.name));
            field.addProperty("fidelity", "recovered");
            fields.add(field);
        }

        typeDecl.add("fields", fields);
        return typeDecl;
    }

    // -----------------------------------------------------------------------
    // Method lifting (MethodNode -> TranspileFunction)
    // -----------------------------------------------------------------------

    private JsonObject liftMethod(ClassNode classNode, MethodNode methodNode,
                                  String qualifiedName, String level) {
        var func = new JsonObject();
        func.addProperty("qualifiedName", qualifiedName);
        func.addProperty("fidelity", "recovered");

        // Return type
        Type returnType = Type.getReturnType(methodNode.desc);
        func.add("returnType", asmTypeToTypeNode(returnType));

        // Parameters
        var params = new JsonArray();
        Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
        List<String> paramNames = resolveParamNames(methodNode, classNode);

        for (int i = 0; i < argTypes.length; i++) {
            var param = new JsonObject();
            param.add("type", asmTypeToTypeNode(argTypes[i]));
            param.addProperty("name", i < paramNames.size()
                    ? paramNames.get(i) : "arg" + i);
            params.add(param);
        }
        func.add("params", params);

        // Body
        var liftedConstructUnsupported = new java.util.LinkedHashSet<String>();
        JsonArray body;
        switch (level) {
            case "structured":
                body = liftMethodBodyStructured(classNode, methodNode,
                        liftedConstructUnsupported);
                break;
            case "idiomatic":
                // L3 idiomatization (temp folding, naming heuristics, dead code elim)
                // runs in topo-core's ModelOptimizer after JSON deserialization.
                // JVMLifter provides the direct-level body; optimizer refines it.
                body = liftMethodBodyDirect(classNode, methodNode);
                break;
            default:
                body = liftMethodBodyDirect(classNode, methodNode);
                break;
        }
        func.add("body", body);

        // Unsupported instructions log (instruction-level + construct-level
        // recovery gaps reported by the structured lifter).
        var unsupported = new java.util.LinkedHashSet<>(collectUnsupported(methodNode));
        unsupported.addAll(liftedConstructUnsupported);
        if (!unsupported.isEmpty()) {
            var unsupportedArr = new JsonArray();
            for (var desc : unsupported) unsupportedArr.add(desc);
            func.add("unsupported", unsupportedArr);
        }

        // Access modifier from bytecode access flags
        String accessMod = accessFlagsToModifier(methodNode.access);
        if (!accessMod.isEmpty()) {
            func.addProperty("accessModifier", accessMod);
        }

        return func;
    }

    /**
     * Direct-level body lifting: walk instructions linearly with stack simulation.
     */
    private JsonArray liftMethodBodyDirect(ClassNode classNode, MethodNode methodNode) {
        var converter = new BytecodeConverter(classNode, methodNode, metadata, reverseNameMap);
        return converter.convert();
    }

    /**
     * Structured-level body lifting: build CFG and recover if/else, for, while patterns.
     */
    private JsonArray liftMethodBodyStructured(ClassNode classNode, MethodNode methodNode,
                                               java.util.Set<String> unsupportedOut) {
        var lifter = new StructuredLifter(classNode, methodNode, metadata, reverseNameMap);
        JsonArray body = lifter.lift();
        unsupportedOut.addAll(lifter.getUnsupportedConstructs());
        return body;
    }

    // -----------------------------------------------------------------------
    // Parameter name resolution
    // -----------------------------------------------------------------------

    private List<String> resolveParamNames(MethodNode methodNode, ClassNode classNode) {
        var names = new ArrayList<String>();
        boolean isStatic = (methodNode.access & 0x0008) != 0;

        // Try LocalVariableTable first (preserves original names if compiled with -g)
        if (methodNode.localVariables != null && !methodNode.localVariables.isEmpty()) {
            // Sort by index to get parameter order
            var sortedLocals = new ArrayList<>(methodNode.localVariables);
            sortedLocals.sort(Comparator.comparingInt(lv -> lv.index));

            int startIdx = isStatic ? 0 : 1; // skip 'this' for instance methods
            Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
            int paramIdx = 0;
            for (var lv : sortedLocals) {
                if (lv.index < startIdx) continue;
                if (paramIdx >= argTypes.length) break;
                // Match local variable slot to parameter index
                if (lv.index == startIdx) {
                    names.add(resolveOriginalName(lv.name));
                    startIdx += argTypes[paramIdx].getSize(); // long/double occupy 2 slots
                    paramIdx++;
                }
            }
        }

        // Try MethodParameters attribute
        if (names.isEmpty() && methodNode.parameters != null) {
            for (var param : methodNode.parameters) {
                names.add(resolveOriginalName(param.name));
            }
        }

        // Try metadata
        if (names.isEmpty()) {
            String metaKey = jvmToQualified(classNode.name) + "." + methodNode.name;
            names = new ArrayList<>(resolveParamNamesFromMetadata(metaKey,
                    Type.getArgumentTypes(methodNode.desc).length));
        }

        return names;
    }

    private List<String> resolveParamNamesFromMetadata(String qualifiedMethod, int paramCount) {
        var names = new ArrayList<String>();
        // Metadata structure: classes -> className -> memberFunctions -> methodName -> params
        if (!metadata.has("classes")) return names;

        // Try to find parameter info in metadata
        var classes = metadata.getAsJsonObject("classes");
        for (var entry : classes.entrySet()) {
            String className = entry.getKey().replace("::", ".");
            if (!qualifiedMethod.startsWith(className)) continue;

            var classObj = entry.getValue().getAsJsonObject();
            if (classObj.has("functions")) {
                var funcs = classObj.getAsJsonObject("functions");
                // Extract method name part
                String methodPart = qualifiedMethod.substring(className.length() + 1);
                if (funcs.has(methodPart)) {
                    var funcObj = funcs.getAsJsonObject(methodPart);
                    if (funcObj.has("params")) {
                        for (var p : funcObj.getAsJsonArray("params")) {
                            if (p.isJsonObject() && p.getAsJsonObject().has("name")) {
                                names.add(p.getAsJsonObject().get("name").getAsString());
                            }
                        }
                    }
                }
            }
        }
        return names;
    }

    // -----------------------------------------------------------------------
    // Name resolution helpers
    // -----------------------------------------------------------------------

    /**
     * Build qualified name for a method: "package.ClassName.methodName"
     * Uses dot notation to match TranspileModel convention.
     */
    private String buildQualifiedName(ClassNode classNode, MethodNode methodNode) {
        String className = jvmToQualified(classNode.name);
        String methodName = resolveOriginalName(methodNode.name);

        // Constructors get a special name
        if (methodNode.name.equals("<init>")) {
            methodName = classNode.name.contains("/")
                    ? classNode.name.substring(classNode.name.lastIndexOf('/') + 1)
                    : classNode.name;
            methodName = resolveOriginalName(methodName);
        }

        return className + "." + methodName;
    }

    /** Convert JVM internal name (com/example/Foo) to qualified name (com.example.Foo). */
    private String jvmToQualified(String internalName) {
        String qualified = internalName.replace('/', '.');
        // Try reverse map for obfuscated classes
        String resolved = reverseNameMap.get(qualified);
        return resolved != null ? resolved : qualified;
    }

    /** Resolve a potentially obfuscated name back to its original. */
    private String resolveOriginalName(String name) {
        String resolved = reverseNameMap.get(name);
        return resolved != null ? resolved : name;
    }

    /** Check if a qualified name matches a requested function name. */
    private boolean matchesRequest(String qualifiedName) {
        for (var req : requestedFunctions) {
            if (qualifiedName.equals(req)) return true;
            // Also match without package prefix
            if (qualifiedName.endsWith("." + req)) return true;
            // Match with :: separator (Topo convention)
            String topoStyle = req.replace("::", ".");
            if (qualifiedName.equals(topoStyle)) return true;
            if (qualifiedName.endsWith("." + topoStyle)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Obfuscation reverse map
    // -----------------------------------------------------------------------

    /**
     * Build a reverse map from obfuscated names to originals using metadata.
     * The metadata may contain an "obfuscation" section with mapping entries.
     */
    private static Map<String, String> buildReverseNameMap(JsonObject metadata) {
        var map = new HashMap<String, String>();

        if (metadata.has("obfuscation")) {
            var obfSection = metadata.getAsJsonObject("obfuscation");
            if (obfSection.has("mapping")) {
                var mapping = obfSection.getAsJsonObject("mapping");
                for (var entry : mapping.entrySet()) {
                    // original -> obfuscated, we want obfuscated -> original
                    map.put(entry.getValue().getAsString(), entry.getKey());
                }
            }
        }

        return map;
    }

    // -----------------------------------------------------------------------
    // Type conversion helpers
    // -----------------------------------------------------------------------

    /** Convert a JVM type descriptor string to TypeNode JSON. */
    static JsonObject descriptorToTypeNode(String descriptor) {
        return asmTypeToTypeNode(Type.getType(descriptor));
    }

    /** Convert an ASM Type to TypeNode JSON (nameParts array). */
    static JsonObject asmTypeToTypeNode(Type type) {
        var node = new JsonObject();
        var nameParts = new JsonArray();

        switch (type.getSort()) {
            case Type.VOID:
                nameParts.add("void");
                break;
            case Type.BOOLEAN:
                nameParts.add("boolean");
                break;
            case Type.BYTE:
                nameParts.add("byte");
                break;
            case Type.CHAR:
                nameParts.add("char");
                break;
            case Type.SHORT:
                nameParts.add("short");
                break;
            case Type.INT:
                nameParts.add("int");
                break;
            case Type.LONG:
                nameParts.add("long");
                break;
            case Type.FLOAT:
                nameParts.add("float");
                break;
            case Type.DOUBLE:
                nameParts.add("double");
                break;
            case Type.ARRAY:
                // Represent as element type with array marker
                var elemNode = asmTypeToTypeNode(type.getElementType());
                var elemParts = elemNode.getAsJsonArray("nameParts");
                if (elemParts.size() > 0) {
                    String last = elemParts.get(elemParts.size() - 1).getAsString();
                    // Append [] for each dimension
                    elemParts.remove(elemParts.size() - 1);
                    elemParts.add(last + "[]".repeat(type.getDimensions()));
                }
                node.add("nameParts", elemParts);
                return node;
            case Type.OBJECT:
                String internalName = type.getInternalName();
                if (internalName.equals("java/lang/String")) {
                    nameParts.add("String");
                } else if (internalName.equals("java/lang/Object")) {
                    nameParts.add("Object");
                } else {
                    // Split into package parts
                    String[] parts = internalName.split("/");
                    for (String part : parts) {
                        nameParts.add(part);
                    }
                }
                break;
            default:
                nameParts.add("unsupported_type");
                break;
        }

        node.add("nameParts", nameParts);
        return node;
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private boolean isSyntheticClass(ClassNode classNode) {
        return (classNode.access & 0x1000) != 0; // ACC_SYNTHETIC
    }

    /**
     * Map JVM access flags to TranspileFunction accessModifier string.
     * Returns "" for package-private (no access flag set).
     */
    private static String accessFlagsToModifier(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) return "public";
        if ((access & Opcodes.ACC_PRIVATE) != 0) return "private";
        if ((access & Opcodes.ACC_PROTECTED) != 0) return "protected";
        return ""; // package-private
    }

    /**
     * Collect descriptions of unsupported bytecode instructions in a method.
     *
     * An INVOKEDYNAMIC backed by the standard
     * {@code java/lang/invoke/LambdaMetafactory.{metafactory,altMetafactory}}
     * bootstrap whose implementation method lives in this class is recovered
     * as a {@code lambda} node by {@link BytecodeConverter}, so it is NOT
     * reported as unsupported. Every other bootstrap (string-concat
     * {@code makeConcatWithConstants}, record bootstraps, unresolvable handles)
     * stays conservatively unsupported.
     */
    private List<String> collectUnsupported(MethodNode methodNode) {
        var unsupported = new LinkedHashSet<String>();
        if (methodNode.instructions == null) return new ArrayList<>();

        for (var iter = methodNode.instructions.iterator(); iter.hasNext(); ) {
            var insn = iter.next();
            switch (insn.getType()) {
                case AbstractInsnNode.TABLESWITCH_INSN:
                case AbstractInsnNode.LOOKUPSWITCH_INSN:
                    // Handled by BytecodeConverter (L1) and StructuredLifter (L2+)
                    break;
                case AbstractInsnNode.INVOKE_DYNAMIC_INSN:
                    if (!isRecoverableLambdaIndy((InvokeDynamicInsnNode) insn,
                            currentClassNode)) {
                        unsupported.add("INVOKEDYNAMIC");
                    }
                    break;
                default:
                    break;
            }
        }
        return new ArrayList<>(unsupported);
    }

    // Class currently being lifted — set per class in lift() so
    // collectUnsupported can resolve lambda impl handles against it.
    private ClassNode currentClassNode;

    /**
     * True iff this invokedynamic is a standard LambdaMetafactory bootstrap
     * whose implementation method resides in {@code owner}, i.e. exactly the
     * case {@link BytecodeConverter#tryBuildLambda} recovers as a lambda node.
     * Mirrors that method's guard conditions so the two stay consistent.
     */
    static boolean isRecoverableLambdaIndy(InvokeDynamicInsnNode insn, ClassNode owner) {
        if (owner == null) return false;
        var bsm = insn.bsm;
        if (bsm == null) return false;
        if (!"java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())) return false;
        String n = bsm.getName();
        if (!"metafactory".equals(n) && !"altMetafactory".equals(n)) return false;
        Object[] a = insn.bsmArgs;
        if (a == null || a.length < 2) return false;
        if (!(a[0] instanceof Type samType) || samType.getSort() != Type.METHOD) return false;
        if (!(a[1] instanceof org.objectweb.asm.Handle h)) return false;
        if (!owner.name.equals(h.getOwner())) return false;
        // Impl method must exist in this class with matching name+desc, and
        // its arity must split cleanly into captures + SAM params.
        if (owner.methods == null) return false;
        for (var m : owner.methods) {
            if (m.name.equals(h.getName()) && m.desc.equals(h.getDesc())) {
                int implArgc = Type.getArgumentTypes(m.desc).length;
                int siteArgc = Type.getArgumentTypes(insn.desc).length;
                int samArgc = samType.getArgumentTypes().length;
                return implArgc == siteArgc + samArgc;
            }
        }
        return false;
    }

    /**
     * Collect {@code name+desc} keys of synthetic lambda-body methods in this
     * class that a recovered LambdaMetafactory invokedynamic embeds inline, so
     * they are suppressed as standalone module functions (no orphan).
     */
    private Set<String> collectInlinedLambdaImpls(ClassNode classNode) {
        var keys = new HashSet<String>();
        for (var m : classNode.methods) {
            if (m.instructions == null) continue;
            for (var it = m.instructions.iterator(); it.hasNext(); ) {
                var insn = it.next();
                if (insn.getType() != AbstractInsnNode.INVOKE_DYNAMIC_INSN) continue;
                var indy = (InvokeDynamicInsnNode) insn;
                if (!isRecoverableLambdaIndy(indy, classNode)) continue;
                var h = (org.objectweb.asm.Handle) indy.bsmArgs[1];
                keys.add(h.getName() + h.getDesc());
            }
        }
        return keys;
    }
}
