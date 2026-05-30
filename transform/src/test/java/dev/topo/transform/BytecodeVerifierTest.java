package dev.topo.transform;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BytecodeVerifier against generated .class bytecode.
 * Uses ASM to synthesize .class files in-memory, writes to temp dir,
 * then verifies against Topo metadata JSON.
 */
class BytecodeVerifierTest {
    private final BytecodeVerifier verifier = new BytecodeVerifier();

    /** Generate a minimal .class file with the given class name and methods. */
    private byte[] generateClass(String internalName, String[] methods, int[] accessFlags) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                 "java/lang/Object", null);

        // Default constructor
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        for (int i = 0; i < methods.length; i++) {
            var method = cw.visitMethod(accessFlags[i], methods[i], "()V", null, null);
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 1);
            method.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private void writeClass(Path dir, String internalName, byte[] bytes) throws IOException {
        Path classFile = dir.resolve(internalName + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
    }

    /** Build a functions Object with a single function entry. */
    private JsonObject buildFunctionEntry(String qualifiedName, String visibility) {
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", qualifiedName);
        int lastSep = qualifiedName.lastIndexOf("::");
        fn.addProperty("simpleName", lastSep >= 0 ? qualifiedName.substring(lastSep + 2) : qualifiedName);
        fn.addProperty("visibility", visibility);
        fn.addProperty("returnType", "void");
        fn.add("params", new JsonArray());
        return fn;
    }

    // --- Check 1: Public symbols exist ---

    @Test
    void publicMethodPresent(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClass("app/Engine",
            new String[]{"process"}, new int[]{Opcodes.ACC_PUBLIC});
        writeClass(tempDir, "app/Engine", cls);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Engine::process", buildFunctionEntry("app::Engine::process", "public"));
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.publicMissing);
    }

    @Test
    void publicMethodMissing(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClass("app/Engine",
            new String[]{"other"}, new int[]{Opcodes.ACC_PUBLIC});
        writeClass(tempDir, "app/Engine", cls);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Engine::process", buildFunctionEntry("app::Engine::process", "public"));
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(1, result.publicMissing);
        assertFalse(result.passed());
    }

    @Test
    void privateMethodNotCheckedAsPublic(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClass("app/Engine",
            new String[]{}, new int[]{});
        writeClass(tempDir, "app/Engine", cls);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Engine::helper", buildFunctionEntry("app::Engine::helper", "private"));
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.publicMissing);
        assertTrue(result.passed());
    }

    // --- Check 2: Logic block consistency ---

    @Test
    void logicBlockCalleesPresent(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClass("app/Engine",
            new String[]{"init", "process"}, new int[]{Opcodes.ACC_PUBLIC, Opcodes.ACC_PUBLIC});
        writeClass(tempDir, "app/Engine", cls);

        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());
        var logicBlocks = new JsonObject();
        var block = new JsonObject();
        block.addProperty("qualifiedName", "app::Engine::run");
        block.addProperty("simpleName", "run");
        var calledFunctions = new JsonArray();
        calledFunctions.add("init");
        calledFunctions.add("process");
        block.add("calledFunctions", calledFunctions);
        var stages = new JsonArray();
        stages.add(1);
        stages.add(2);
        block.add("stages", stages);
        block.addProperty("isPipeline", false);
        block.add("edges", new JsonArray());
        logicBlocks.add("app::Engine::run", block);
        metadata.add("logicBlocks", logicBlocks);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.blockMismatches);
    }

    @Test
    void logicBlockCalleeMissing(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClass("app/Engine",
            new String[]{"init"}, new int[]{Opcodes.ACC_PUBLIC});
        writeClass(tempDir, "app/Engine", cls);

        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());
        var logicBlocks = new JsonObject();
        var block = new JsonObject();
        block.addProperty("qualifiedName", "app::Engine::run");
        block.addProperty("simpleName", "run");
        var calledFunctions = new JsonArray();
        calledFunctions.add("init");
        calledFunctions.add("missing");
        block.add("calledFunctions", calledFunctions);
        var stages = new JsonArray();
        stages.add(1);
        stages.add(2);
        block.add("stages", stages);
        block.addProperty("isPipeline", false);
        block.add("edges", new JsonArray());
        logicBlocks.add("app::Engine::run", block);
        metadata.add("logicBlocks", logicBlocks);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(1, result.blockMismatches);
    }

    // --- Empty inputs ---

    @Test
    void emptyMetadataPasses(@TempDir Path tempDir) throws IOException {
        var metadata = new JsonObject();
        var result = verifier.verify(tempDir, metadata);
        assertTrue(result.passed());
    }

    @Test
    void noClassFilesPasses(@TempDir Path tempDir) throws IOException {
        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());
        var result = verifier.verify(tempDir, metadata);
        assertTrue(result.passed());
    }

    // --- Loader failure surfacing ---

    /**
     * A malformed .class file (truncated or otherwise unparseable by ASM)
     * MUST surface as a {@code classLoadFailures} count and fail
     * verification — previously the {@code IllegalArgumentException}
     * ASM throws was uncaught (crashing the verifier) and the
     * {@code IOException} path was a silent {@code System.err.println}
     * skip, both of which let the resulting "method missing" reports be
     * indistinguishable from "javac never produced it".
     */
    @Test
    void malformedClassFileSurfacesAsLoadFailure(@TempDir Path tempDir) throws IOException {
        // Truncated class file — first four bytes (CAFEBABE magic) but
        // nothing after. ASM's ClassReader rejects it before producing a
        // ClassNode.
        Path bogus = tempDir.resolve("Broken.class");
        Files.write(bogus, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Engine::process", buildFunctionEntry("app::Engine::process", "public"));
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(1, result.classLoadFailures,
            "malformed .class file should be recorded as a load failure");
        assertFalse(result.passed(),
            "load failure must not silently downgrade to a missing-method report");
        assertTrue(result.errors.stream().anyMatch(e -> e.contains("class-load failure")),
            "load failure should appear in the structured errors list: " + result.errors);
    }

    // --- Namespace-level function resolution via class scan ---

    @Test
    void namespaceLevelFunctionsResolvedViaClassScan(@TempDir Path tempDir) throws IOException {
        // Simulate stages scenario: functions are "app::init", "app::process" (namespace-level)
        // but actual class is app/Main
        byte[] cls = generateClass("app/Main",
            new String[]{"init", "process"}, new int[]{Opcodes.ACC_PUBLIC, Opcodes.ACC_PUBLIC});
        writeClass(tempDir, "app/Main", cls);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::init", buildFunctionEntry("app::init", "public"));
        functions.add("app::process", buildFunctionEntry("app::process", "public"));
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.publicMissing, "namespace-level functions should resolve via .class scan");
        assertTrue(result.passed());
    }

    // --- Pipeline fn block: calledFunctions are already fully qualified ---

    // --- Check 7: Pipeline edge structural verification ---

    private JsonObject buildPipelineBlock(String blockName, String[] nodes,
                                          Object[][] edgeSpecs) {
        var block = new JsonObject();
        block.addProperty("qualifiedName", blockName);
        block.addProperty("simpleName", blockName.contains("::")
            ? blockName.substring(blockName.lastIndexOf("::") + 2) : blockName);
        block.addProperty("isPipeline", true);
        var cf = new JsonArray();
        for (var n : nodes) cf.add(n);
        block.add("calledFunctions", cf);
        block.add("stages", new JsonArray());
        var edges = new JsonArray();
        for (var spec : edgeSpecs) {
            var e = new JsonObject();
            e.addProperty("source", (String) spec[0]);
            e.addProperty("target", (String) spec[1]);
            if (spec.length > 2 && Boolean.TRUE.equals(spec[2])) {
                e.addProperty("isTerminal", true);
                if (spec.length > 3) e.addProperty("terminalType", (String) spec[3]);
            }
            edges.add(e);
        }
        block.add("edges", edges);
        return block;
    }

    @Test
    void pipelineEdgeEndpointsPresent(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClass("app/Main",
            new String[]{"parse", "transform", "emit"},
            new int[]{Opcodes.ACC_PRIVATE, Opcodes.ACC_PRIVATE, Opcodes.ACC_PRIVATE});
        writeClass(tempDir, "app/Main", cls);

        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());
        var logicBlocks = new JsonObject();
        logicBlocks.add("app::run", buildPipelineBlock("app::run",
            new String[]{"app::parse", "app::transform", "app::emit"},
            new Object[][]{
                {"app::parse", "app::transform"},
                {"app::transform", "app::emit"},
                {"app::emit", "std::cpp17::int", Boolean.TRUE, "std::cpp17::int"},
            }));
        metadata.add("logicBlocks", logicBlocks);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.pipelineEdgeMismatches,
            "all non-terminal edge endpoints should resolve");
    }

    @Test
    void pipelineEdgeTargetMissingReported(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClass("app/Main",
            new String[]{"parse"}, new int[]{Opcodes.ACC_PRIVATE});
        writeClass(tempDir, "app/Main", cls);

        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());
        var logicBlocks = new JsonObject();
        logicBlocks.add("app::run", buildPipelineBlock("app::run",
            new String[]{"app::parse", "app::missing"},
            new Object[][]{
                {"app::parse", "app::missing"},
            }));
        metadata.add("logicBlocks", logicBlocks);

        var result = verifier.verify(tempDir, metadata);
        assertTrue(result.pipelineEdgeMismatches >= 1,
            "missing pipeline edge target must be flagged");
    }

    @Test
    void pipelineEdgeTerminalTargetSkipped(@TempDir Path tempDir) throws IOException {
        // Terminal edge target is a type name (e.g. "std::cpp17::int"), not a
        // method — verifier must not try to resolve it.
        byte[] cls = generateClass("app/Main",
            new String[]{"emit"}, new int[]{Opcodes.ACC_PRIVATE});
        writeClass(tempDir, "app/Main", cls);

        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());
        var logicBlocks = new JsonObject();
        logicBlocks.add("app::run", buildPipelineBlock("app::run",
            new String[]{"app::emit"},
            new Object[][]{
                {"app::emit", "std::cpp17::int", Boolean.TRUE, "std::cpp17::int"},
            }));
        metadata.add("logicBlocks", logicBlocks);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.pipelineEdgeMismatches,
            "terminal edge target is a type, not a method — must be skipped");
    }

    @Test
    void nonPipelineBlockEdgesIgnored(@TempDir Path tempDir) throws IOException {
        // Only isPipeline=true blocks should have their edges checked.
        byte[] cls = generateClass("app/Main",
            new String[]{}, new int[]{});
        writeClass(tempDir, "app/Main", cls);

        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());
        var logicBlocks = new JsonObject();
        var block = new JsonObject();
        block.addProperty("qualifiedName", "app::run");
        block.addProperty("simpleName", "run");
        block.addProperty("isPipeline", false);
        block.add("calledFunctions", new JsonArray());
        block.add("stages", new JsonArray());
        // Even if edges were present, they should be ignored for non-pipeline blocks
        var edges = new JsonArray();
        var e = new JsonObject();
        e.addProperty("source", "app::ghost");
        e.addProperty("target", "app::phantom");
        edges.add(e);
        block.add("edges", edges);
        logicBlocks.add("app::run", block);
        metadata.add("logicBlocks", logicBlocks);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.pipelineEdgeMismatches);
    }

    @Test
    void pipelineBlockCalleesNotDoubleQualified(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClass("app/Main",
            new String[]{"parse", "transform", "emit"},
            new int[]{Opcodes.ACC_PRIVATE, Opcodes.ACC_PRIVATE, Opcodes.ACC_PRIVATE});
        writeClass(tempDir, "app/Main", cls);

        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());
        var logicBlocks = new JsonObject();
        var block = new JsonObject();
        block.addProperty("qualifiedName", "app::run");
        block.addProperty("simpleName", "run");
        block.addProperty("isPipeline", true);
        // Pipeline calledFunctions are already fully qualified
        var calledFunctions = new JsonArray();
        calledFunctions.add("app::parse");
        calledFunctions.add("app::transform");
        calledFunctions.add("app::emit");
        block.add("calledFunctions", calledFunctions);
        block.add("stages", new JsonArray());
        block.add("edges", new JsonArray());
        logicBlocks.add("app::run", block);
        metadata.add("logicBlocks", logicBlocks);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.blockMismatches,
            "pipeline block callees should not be double-qualified");
        assertTrue(result.passed());
    }

    @Test
    void pipelineEdgeSimpleNamesQualifiedByBlockNamespace(@TempDir Path tempDir) throws IOException {
        // Regression test for the real parser output: pipeline edges store
        // endpoint names *as the user wrote them* — typically simple names
        // because the DAG lives inside a namespace/class scope. The verifier
        // must qualify them against the enclosing block's namespace before
        // resolving them in the method index. Mirrors
        // topo-jvm/benchmarks/pipeline/topo/main.topo under Topo-base.toml.
        byte[] cls = generateClass("app/Main",
            new String[]{"parse", "enhance", "detect", "compose"},
            new int[]{Opcodes.ACC_PRIVATE, Opcodes.ACC_PRIVATE,
                      Opcodes.ACC_PRIVATE, Opcodes.ACC_PRIVATE});
        writeClass(tempDir, "app/Main", cls);

        var metadata = new JsonObject();
        metadata.add("functions", new JsonObject());
        var logicBlocks = new JsonObject();
        // Simple (unqualified) names like the parser emits:
        //     parse -> enhance;  parse -> detect;
        //     enhance -> compose;  detect -> compose;
        //     compose -> Int;  (terminal)
        logicBlocks.add("app::process", buildPipelineBlock("app::process",
            new String[]{"app::parse", "app::enhance", "app::detect", "app::compose"},
            new Object[][]{
                {"parse", "enhance"},
                {"parse", "detect"},
                {"enhance", "compose"},
                {"detect", "compose"},
                {"compose", "std::cpp17::int", Boolean.TRUE, "std::cpp17::int"},
            }));
        metadata.add("logicBlocks", logicBlocks);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.pipelineEdgeMismatches,
            "unqualified edge names should resolve via block namespace");
        assertTrue(result.passed(),
            "pipeline edge verification should pass when endpoints are simple names");
    }

    // --- MethodKey-based overload disambiguation ---

    /**
     * Build a class with two methods of the same name and differing
     * descriptors. The pre-MethodKey verifier collapsed both into the
     * same {@code methodIndex} slot, so Check 1 could only see one of
     * them and the other was silently dropped from validation. After
     * the MethodKey refactor both overloads occupy distinct slots and
     * a descriptor-less {@code .topo} declaration matches both via
     * {@code nameIndex}.
     */
    private byte[] generateClassWithOverloads(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                 "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        for (String desc : new String[]{"(I)V", "(J)V"}) {
            var m = cw.visitMethod(Opcodes.ACC_PUBLIC, "process", desc, null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, desc.contains("J") ? 3 : 2);
            m.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void overloadedPublicMethodPassesCheck1(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClassWithOverloads("app/Engine");
        writeClass(tempDir, "app/Engine", cls);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Engine::process",
            buildFunctionEntry("app::Engine::process", "public"));
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.publicMissing,
            "descriptor-less declaration should match any overload");
    }

    @Test
    void check3OverloadAmbiguityProducesNote(@TempDir Path tempDir) throws IOException {
        byte[] cls = generateClassWithOverloads("app/Engine");
        writeClass(tempDir, "app/Engine", cls);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        var fn = buildFunctionEntry("app::Engine::process", "public");
        // Declare one parameter — both overloads have arity 1, so Check 3
        // matches both; a note must be emitted reporting the ambiguity.
        var params = new JsonArray();
        var p = new JsonObject();
        p.addProperty("name", "x");
        p.addProperty("type", "int");
        params.add(p);
        fn.add("params", params);
        functions.add("app::Engine::process", fn);
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(0, result.signatureMismatches,
            "at least one overload matches the declared arity → no signature mismatch");
        boolean hasNote = result.errors.stream()
            .anyMatch(s -> s.startsWith("note:") && s.contains("overloaded"));
        assertTrue(hasNote,
            "ambiguous overload resolution must emit a note — name-only " +
            ".topo declarations apply to every overload by design");
    }

    @Test
    void check1MissingOverloadNamePass(@TempDir Path tempDir) throws IOException {
        // No methods named "missing" anywhere — declaration must fail.
        byte[] cls = generateClassWithOverloads("app/Engine");
        writeClass(tempDir, "app/Engine", cls);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Engine::missing",
            buildFunctionEntry("app::Engine::missing", "public"));
        metadata.add("functions", functions);

        var result = verifier.verify(tempDir, metadata);
        assertEquals(1, result.publicMissing,
            "an entirely-unknown method name must still be flagged");
    }
}
