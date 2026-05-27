package dev.topo.transform;

import com.google.gson.*;
import dev.topo.transform.pass.MethodKey;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Verifies compiled .class files against Topo symbol table metadata.
 *
 * <p>Runs the 9 implemented checks adapted for JVM bytecode (the
 * historical "10 verification checks" wording is retired — Check 9
 * constraint satisfaction is delegated to javac and no longer occupies
 * a numbered slot; see {@code VerifyResult.javacDeferredNote}). Check
 * 10 is internally a heuristic — see
 * {@link #checkSharedMutableFieldWrites} — and its
 * {@code VerifyResult} counter is named accordingly.</p>
 *
 * <p>Method indexing keys on {@link MethodKey} {@code (owner, name,
 * descriptor)} so JVM-legal overloads remain distinct. Lookups from
 * {@code .topo} declarations (which today carry no descriptor) go
 * through {@link #lookupMethod}, which uses the wildcard form of
 * {@link MethodKey} and emits a {@link VerifyResult#errors} entry when
 * a name resolves to more than one overload.</p>
 */
public class BytecodeVerifier {

    public BytecodeVerifier() {
    }

    public VerifyResult verify(Path classDir, JsonObject metadata) throws IOException {
        var result = new VerifyResult();

        // Parse metadata (Object format, keyed by qualifiedName)
        var functionsObj = metadata.has("functions") ? metadata.getAsJsonObject("functions") : new JsonObject();
        var logicBlocksObj = metadata.has("logicBlocks") ? metadata.getAsJsonObject("logicBlocks") : new JsonObject();

        // Collect all class nodes from .class files. Load failures are
        // recorded as structured diagnostics so downstream "method missing"
        // reports remain distinguishable from "javac never produced this".
        var loadResult = loadClassNodesTracked(classDir);
        var classNodes = loadResult.nodes;
        for (var failure : loadResult.failures) {
            result.classLoadFailures++;
            result.errors.add("class-load failure: " + failure);
        }

        // Create mapper with full metadata + class scan context
        var mapper = new BytecodeSymbolMapper(metadata, classNodes);

        // Build method index keyed by (owner, name, descriptor) so JVM-legal
        // overloads remain distinct slots. nameIndex tracks all keys per
        // (owner, name) for wildcard lookups from descriptor-less .topo
        // declarations; ambiguity is reported, not silently resolved.
        var methodIndex = new HashMap<MethodKey, MethodNode>();
        var nameIndex = new HashMap<String, List<MethodKey>>();
        var classIndex = new HashMap<String, ClassNode>();
        for (var cn : classNodes) {
            classIndex.put(cn.name, cn);
            for (var mn : cn.methods) {
                MethodKey key = MethodKey.of(cn.name, mn.name, mn.desc);
                methodIndex.put(key, mn);
                nameIndex.computeIfAbsent(cn.name + "/" + mn.name, k -> new ArrayList<>()).add(key);
            }
        }

        // Check 1: Public symbols exist
        for (var entry : functionsObj.entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            String name = fn.get("qualifiedName").getAsString();
            String visibility = fn.has("visibility") ? fn.get("visibility").getAsString() : "public";

            if (!"public".equals(visibility)) continue;

            String jvmClass = mapper.toJvmClassName(name);
            String methodName = mapper.extractMethodName(name);
            String nameKey = jvmClass + "/" + methodName;

            if (!nameIndex.containsKey(nameKey)) {
                result.publicMissing++;
                result.errors.add("public method missing: " + name + " (expected " + nameKey + ")");
            }
        }

        // Check 2: Logic block consistency (method call graph)
        for (var blockEntry : logicBlocksObj.entrySet()) {
            var block = blockEntry.getValue().getAsJsonObject();
            if (!block.has("calledFunctions")) continue;
            var calledFunctions = block.getAsJsonArray("calledFunctions");
            // Pipeline fn blocks store fully-qualified callee names; skip namespace prefixing
            boolean isPipeline = block.has("isPipeline") && block.get("isPipeline").getAsBoolean();
            // Derive namespace from block's qualifiedName
            String blockQName = block.get("qualifiedName").getAsString();
            String namespace = blockQName.contains("::")
                ? blockQName.substring(0, blockQName.lastIndexOf("::"))
                : "";

            for (var calleeEl : calledFunctions) {
                String simpleName = calleeEl.getAsString();
                // Pipeline blocks: use name as-is; normal blocks: qualify with namespace
                String callee = isPipeline ? simpleName
                    : (namespace.isEmpty() ? simpleName : namespace + "::" + simpleName);
                String calleeMethod = mapper.extractMethodName(callee);
                String calleeClass = mapper.toJvmClassName(callee);
                String key = calleeClass + "/" + calleeMethod;

                if (!nameIndex.containsKey(key)) {
                    result.blockMismatches++;
                    result.errors.add("logic block callee missing: " + callee);
                }
            }
        }

        // Check 3: Signature matching (parameter/return types via type mapping)
        // For each declaration, look up *all* overloads sharing the
        // declaration's name and require at least one to satisfy the
        // declared param count. When more than one overload matches,
        // emit a Note that the .topo declaration is ambiguous; the
        // declaration syntax has no per-overload disambiguator, so the
        // caller-boundary contract is "name applies to all overloads".
        for (var entry : functionsObj.entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            String name = fn.get("qualifiedName").getAsString();
            String jvmClass = mapper.toJvmClassName(name);
            String methodName = mapper.extractMethodName(name);
            var keys = nameIndex.get(jvmClass + "/" + methodName);
            if (keys == null || keys.isEmpty()) continue;

            if (!fn.has("params")) continue;
            var params = fn.getAsJsonArray("params");
            int declaredArity = params.size();

            int matched = 0;
            int firstArity = -1;
            for (var k : keys) {
                Type[] argTypes = Type.getArgumentTypes(k.descriptor());
                if (argTypes.length == declaredArity) matched++;
                if (firstArity < 0) firstArity = argTypes.length;
            }

            if (matched == 0) {
                result.signatureMismatches++;
                result.errors.add("parameter count mismatch for " + name +
                    ": declared " + declaredArity + ", found " + firstArity +
                    (keys.size() > 1 ? " (no overload matches)" : ""));
            } else if (keys.size() > 1) {
                result.errors.add("note: " + name +
                    " is overloaded (" + keys.size() + " overloads); " +
                    matched + " match the declared param count — the .topo " +
                    "declaration applies to all of them");
            }
        }

        // Check 4: Const consistency (final modifier)
        // A descriptor-less .topo declaration applies to every overload of
        // the same name; require every overload to satisfy the constraint
        // and report the specific overload that fails.
        for (var entry : functionsObj.entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            if (!fn.has("isConst") || !fn.get("isConst").getAsBoolean()) continue;

            String name = fn.get("qualifiedName").getAsString();
            String jvmClass = mapper.toJvmClassName(name);
            String methodName = mapper.extractMethodName(name);
            var keys = nameIndex.get(jvmClass + "/" + methodName);
            if (keys == null) continue;

            for (var k : keys) {
                var mn = methodIndex.get(k);
                if (mn == null) continue;
                if (!mapper.isFinal(mn.access)) {
                    result.constMismatches++;
                    result.errors.add("const mismatch for " + name +
                        " (overload " + k + "): declared const but method is not final");
                }
            }
        }

        // Check 5: Class member existence (only for public/protected symbols that must exist)
        for (var entry : functionsObj.entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            String visibility = fn.has("visibility") ? fn.get("visibility").getAsString() : "public";
            if ("private".equals(visibility) || "internal".equals(visibility)) continue;

            String qualifiedName = fn.get("qualifiedName").getAsString();
            // Derive className from qualifiedName: everything except the last segment
            int lastSep = qualifiedName.lastIndexOf("::");
            if (lastSep < 0) continue;
            String className = qualifiedName.substring(0, lastSep);
            String jvmClass = className.replace("::", "/");

            if (!classIndex.containsKey(jvmClass)) continue;

            String methodName = mapper.extractMethodName(qualifiedName);
            var cn = classIndex.get(jvmClass);
            boolean found = cn.methods.stream().anyMatch(m -> m.name.equals(methodName));
            if (!found) {
                result.classMemberMissing++;
                result.errors.add("class member missing: " + methodName + " in " + className);
            }
        }

        // Check 6: Stage order (call graph topological sort vs stage constraints)
        Map<String, Integer> stageMap = buildStageMap(logicBlocksObj);
        checkStageOrder(result, functionsObj, stageMap, methodIndex, nameIndex, mapper);

        // Check 7: Pipeline edges (DAG structure preserved)
        checkPipelineEdges(result, metadata, nameIndex, mapper);

        // Check 8: Template instantiation (generic erasure -> class exists)
        if (metadata.has("typeAliases")) {
            for (var entry : metadata.getAsJsonObject("typeAliases").entrySet()) {
                var alias = entry.getValue().getAsJsonObject();
                if (!alias.has("templateArgs")) continue;
                String typeName = alias.get("qualifiedName").getAsString();
                String jvmClass = mapper.toJvmClassName(typeName);
                // After erasure, the raw class should exist
                if (!classIndex.containsKey(jvmClass)) {
                    result.templateInstantiationMissing++;
                    result.errors.add("generic type not found after erasure: " + typeName);
                }
            }
        }

        // Check 9 (delegated): constraint satisfaction is checked by
        // javac during host compilation. There is no Topo-side failure
        // mode here; the field is documented in VerifyResult only as a
        // delegation note, not a counter.

        // Check 10 (heuristic): shared-mutable-field write detection.
        // This is NOT a general data-race / "parallel safety" check —
        // it only flags fields written by two or more methods scheduled
        // to the same parallel stage. The authoritative purity guarantee
        // lives in topo-core's PurityCheck (declaration-level call-graph
        // analysis); this JVM-side check is defence in depth for the
        // pairwise-write case. The historical "Check 10: Parallel safety"
        // wording overstated the coverage — see
        // jvm-bytecode-verifier-parallel-safety-misnamed.
        checkSharedMutableFieldWrites(result, logicBlocksObj, methodIndex, nameIndex, mapper);

        return result;
    }

    /**
     * Builds a map from qualified function name to stage number,
     * extracted from logicBlocks.
     */
    private Map<String, Integer> buildStageMap(JsonObject logicBlocksObj) {
        Map<String, Integer> map = new HashMap<>();
        for (var entry : logicBlocksObj.entrySet()) {
            var block = entry.getValue().getAsJsonObject();
            if (!block.has("calledFunctions") || !block.has("stages")) continue;
            var calledFunctions = block.getAsJsonArray("calledFunctions");
            var stages = block.getAsJsonArray("stages");
            String blockQName = block.get("qualifiedName").getAsString();
            String namespace = blockQName.contains("::")
                ? blockQName.substring(0, blockQName.lastIndexOf("::"))
                : "";
            for (int i = 0; i < calledFunctions.size() && i < stages.size(); i++) {
                String simpleName = calledFunctions.get(i).getAsString();
                String qualifiedCallee = namespace.isEmpty() ? simpleName : namespace + "::" + simpleName;
                map.put(qualifiedCallee, stages.get(i).getAsInt());
            }
        }
        return map;
    }

    /**
     * Loader result paired with structured load failures so callers can
     * surface them as verification errors instead of letting them silently
     * downgrade to "method missing".
     */
    static final class LoadResult {
        final List<ClassNode> nodes;
        final List<String> failures;

        LoadResult(List<ClassNode> nodes, List<String> failures) {
            this.nodes = nodes;
            this.failures = failures;
        }
    }

    /**
     * Load every .class file under {@code classDir}, tracking IO and
     * malformed-bytecode failures. Catches {@link IOException} (the I/O
     * failure surface) plus the unchecked exceptions ASM's
     * {@code ClassReader} can throw on corrupt class files or unsupported
     * class-major versions — at minimum {@link IllegalArgumentException}
     * and {@link ArrayIndexOutOfBoundsException} (truncated constant
     * pool / header). All previously turned into a silent
     * {@code System.err.println} skip or an uncaught crash, both of
     * which left downstream checks unable to tell "javac never produced
     * this" from "loader couldn't read it".
     */
    static LoadResult loadClassNodesTracked(Path classDir) throws IOException {
        var nodes = new ArrayList<ClassNode>();
        var failures = new ArrayList<String>();
        if (!Files.exists(classDir)) return new LoadResult(nodes, failures);

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
        return new LoadResult(nodes, failures);
    }

    private void checkStageOrder(VerifyResult result, JsonObject functionsObj,
                                  Map<String, Integer> stageMap,
                                  Map<MethodKey, MethodNode> methodIndex,
                                  Map<String, List<MethodKey>> nameIndex,
                                  BytecodeSymbolMapper mapper) {
        // For each function that has a stage, check that it doesn't call
        // methods from later stages. A descriptor-less .topo qualified
        // name applies to every overload sharing the name — scan all of
        // them for stage-violating calls.
        for (var entry : functionsObj.entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            String name = fn.get("qualifiedName").getAsString();
            if (!stageMap.containsKey(name)) continue;
            int myStage = stageMap.get(name);

            String jvmClass = mapper.toJvmClassName(name);
            String methodName = mapper.extractMethodName(name);
            var keys = nameIndex.get(jvmClass + "/" + methodName);
            if (keys == null) continue;

            for (var k : keys) {
                var mn = methodIndex.get(k);
                if (mn == null || mn.instructions == null) continue;

                // Scan for invoke instructions
                for (var insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode call) {
                        String calleeName = call.owner.replace("/", "::") + "::" + call.name;
                        Integer calleeStage = stageMap.get(calleeName);
                        if (calleeStage != null && calleeStage > myStage) {
                            result.stageOrderViolations++;
                            result.errors.add("stage order violation: " + name + " (stage " + myStage +
                                ") calls " + calleeName + " (stage " + calleeStage + ")");
                        }
                    }
                }
            }
        }
    }

    private void checkPipelineEdges(VerifyResult result, JsonObject metadata,
                                     Map<String, List<MethodKey>> nameIndex,
                                     BytecodeSymbolMapper mapper) {
        // Pipeline edges live inside logicBlocks[qualifiedName].edges[*] with
        // {source, target, isTerminal?, terminalType?}. The parser stores edge
        // endpoints with whatever qualification the user wrote — typically a
        // simple name like "parse" because pipeline bodies live inside a
        // namespace/class scope. Qualify simple names against the enclosing
        // block's namespace (mirroring the logic used for `calledFunctions` in
        // Check 2) before resolving them in the method index.
        if (!metadata.has("logicBlocks")) return;
        var logicBlocksObj = metadata.getAsJsonObject("logicBlocks");

        for (var blockEntry : logicBlocksObj.entrySet()) {
            var block = blockEntry.getValue().getAsJsonObject();
            boolean isPipeline = block.has("isPipeline") && block.get("isPipeline").getAsBoolean();
            if (!isPipeline || !block.has("edges")) continue;

            // Derive namespace from block's qualifiedName (e.g. "app::process" -> "app").
            String blockQName = block.get("qualifiedName").getAsString();
            String namespace = blockQName.contains("::")
                ? blockQName.substring(0, blockQName.lastIndexOf("::"))
                : "";

            var edges = block.getAsJsonArray("edges");
            for (var edgeEl : edges) {
                var edge = edgeEl.getAsJsonObject();
                if (!edge.has("source")) continue;
                String rawSource = edge.get("source").getAsString();
                String source = qualifyEdgeEndpoint(rawSource, namespace);
                boolean isTerminal = edge.has("isTerminal") && edge.get("isTerminal").getAsBoolean();

                // Source must always be a real method. Pipeline edges
                // carry no descriptor — a name match against any overload
                // is enough to satisfy edge existence.
                String srcClass = mapper.toJvmClassName(source);
                String srcMethod = mapper.extractMethodName(source);
                if (!nameIndex.containsKey(srcClass + "/" + srcMethod)) {
                    result.pipelineEdgeMismatches++;
                    result.errors.add("pipeline edge source missing: " + rawSource);
                }

                // Terminal edges: target is a type name (e.g. "std::cpp17::int"),
                // not a method — skip endpoint-existence check on the target.
                if (isTerminal) continue;

                if (!edge.has("target")) continue;
                String rawTarget = edge.get("target").getAsString();
                if (rawTarget.isEmpty()) continue;
                String target = qualifyEdgeEndpoint(rawTarget, namespace);

                String tgtClass = mapper.toJvmClassName(target);
                String tgtMethod = mapper.extractMethodName(target);
                if (!nameIndex.containsKey(tgtClass + "/" + tgtMethod)) {
                    result.pipelineEdgeMismatches++;
                    result.errors.add("pipeline edge target missing: " + rawSource + " -> " + rawTarget);
                }
            }
        }
    }

    /**
     * Qualify a pipeline edge endpoint name against the enclosing block's
     * namespace. Endpoints that already contain "::" are returned as-is;
     * simple names get the namespace prepended (or are returned as-is when the
     * block has no namespace).
     */
    private static String qualifyEdgeEndpoint(String name, String namespace) {
        if (name == null || name.isEmpty()) return name;
        if (name.contains("::")) return name;
        if (namespace.isEmpty()) return name;
        return namespace + "::" + name;
    }

    /**
     * Shared-mutable-field write detection (heuristic).
     *
     * <p>For each parallel stage (a {@code calledFunctions} group of size
     * &geq; 2 within one {@code logicBlocks} entry), flag fields written
     * by two or more methods in that group. This is a narrow pairwise
     * write check, NOT a general data-race detector:</p>
     *
     * <ul>
     *   <li>A single method writing a shared field that other parallel
     *       methods <em>read</em> is not flagged.</li>
     *   <li>Reads ({@code GETFIELD}/{@code GETSTATIC}) of mutated fields
     *       are not flagged.</li>
     *   <li>Aliased writes through method calls are not flagged (the scan
     *       sees only the immediate bytecode in the parallel-stage
     *       methods themselves, not their callees).</li>
     * </ul>
     *
     * <p>The canonical purity guarantee lives in topo-core's
     * {@code PurityCheck}, which walks the declaration-class call graph.
     * This JVM-side check is defence-in-depth for the pairwise-write
     * pattern. See {@code jvm-bytecode-verifier-parallel-safety-misnamed}
     * for the rename history.</p>
     */
    private void checkSharedMutableFieldWrites(VerifyResult result, JsonObject logicBlocksObj,
                                                Map<MethodKey, MethodNode> methodIndex,
                                                Map<String, List<MethodKey>> nameIndex,
                                                BytecodeSymbolMapper mapper) {
        // Find parallel stages: for each logic block, group functions by stage.
        // Stages with 2+ functions are parallelizable.
        var stageGroups = new HashMap<Integer, List<String>>();

        for (var blockEntry : logicBlocksObj.entrySet()) {
            var block = blockEntry.getValue().getAsJsonObject();
            if (!block.has("calledFunctions") || !block.has("stages")) continue;
            var calledFunctions = block.getAsJsonArray("calledFunctions");
            var stages = block.getAsJsonArray("stages");
            String blockQName = block.get("qualifiedName").getAsString();
            String namespace = blockQName.contains("::")
                ? blockQName.substring(0, blockQName.lastIndexOf("::"))
                : "";
            // Group by stage within this block
            var blockStageGroups = new HashMap<Integer, List<String>>();
            for (int i = 0; i < calledFunctions.size() && i < stages.size(); i++) {
                String simpleName = calledFunctions.get(i).getAsString();
                String qualifiedCallee = namespace.isEmpty() ? simpleName : namespace + "::" + simpleName;
                int stage = stages.get(i).getAsInt();
                blockStageGroups.computeIfAbsent(stage, k -> new ArrayList<>()).add(qualifiedCallee);
            }
            // Merge into overall groups (only stages with 2+ functions)
            for (var group : blockStageGroups.entrySet()) {
                if (group.getValue().size() >= 2) {
                    stageGroups.computeIfAbsent(group.getKey(), k -> new ArrayList<>())
                        .addAll(group.getValue());
                }
            }
        }

        // For each parallel stage group, check for shared mutable state.
        // Methods declared without a descriptor expand to every overload;
        // scan each overload independently for field writes.
        for (var group : stageGroups.values()) {
            if (group.size() < 2) continue;

            var writtenFields = new HashSet<String>();
            for (String name : group) {
                String jvmClass = mapper.toJvmClassName(name);
                String methodName = mapper.extractMethodName(name);
                var keys = nameIndex.get(jvmClass + "/" + methodName);
                if (keys == null) continue;
                for (var k : keys) {
                    var mn = methodIndex.get(k);
                    if (mn == null || mn.instructions == null) continue;

                    for (var insn : mn.instructions) {
                        if (insn instanceof FieldInsnNode field) {
                            if (insn.getOpcode() == Opcodes.PUTFIELD ||
                                insn.getOpcode() == Opcodes.PUTSTATIC) {
                                String fieldKey = field.owner + "." + field.name;
                                if (!writtenFields.add(fieldKey)) {
                                    result.sharedMutableFieldWrites++;
                                    result.errors.add("shared-mutable-field write: " +
                                        fieldKey + " written by multiple methods in the same parallel stage");
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
