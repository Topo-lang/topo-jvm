package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Unit tests for {@link PipelinePass}.
 *
 * Each case constructs a standalone class with stage methods and an orchestrator
 * method, runs the pass against metadata mirroring what the frontend emits
 * (logicBlocks[*].isPipeline + edges + pipelineAnalysis), then either inspects
 * the rewritten bytecode for expected shape (INVOKEDYNAMIC + CompletableFuture
 * method refs) or loads the transformed class and invokes it to assert the
 * orchestrator still produces the same integer result as a direct sequential
 * chain of the same stage functions.
 */
class PipelinePassTest {

    private static final String CF = "java/util/concurrent/CompletableFuture";

    /** Fork/join DAG: parse → {enhance, detect} → compose. Mirrors the benchmark. */
    @Test
    void forkJoinOrchestratorRewritten() {
        String className = "app/ForkJoin";
        byte[] original = generateForkJoinClass(className);
        JsonObject metadata = buildForkJoinMetadata(className);

        byte[] transformed = applyPass(original, metadata);

        ClassNode cn = toClassNode(transformed);
        MethodNode mn = findMethod(cn, "process");
        assertNotNull(mn, "orchestrator method must survive rewrite");

        // Three supplyAsync/thenApplyAsync/thenCombine + one join should appear.
        assertTrue(countMethodInsn(mn, INVOKESTATIC, CF, "supplyAsync") >= 1,
                "at least one supplyAsync for the source node");
        // enhance and detect are both single-pred of parse → two thenApplyAsync.
        assertTrue(countMethodInsn(mn, INVOKEVIRTUAL, CF, "thenApplyAsync") >= 2,
                "single-pred successors should use thenApplyAsync");
        assertTrue(countMethodInsn(mn, INVOKEVIRTUAL, CF, "thenCombine") >= 1,
                "two-pred join (compose) should use thenCombine");
        assertTrue(countMethodInsn(mn, INVOKEVIRTUAL, CF, "join") >= 1,
                "terminal future should be joined at the end");

        // Synthetic bridges for parse (Supplier), enhance/detect (Function),
        // compose (BiFunction) must be present.
        assertTrue(findBridges(cn, "$topo$pipe$src$").size() >= 1,
                "supplier bridge for source node");
        assertTrue(findBridges(cn, "$topo$pipe$fn$").size() >= 2,
                "function bridges for single-pred nodes");
        assertTrue(findBridges(cn, "$topo$pipe$bifn$").size() >= 1,
                "bifunction bridge for two-pred node");
    }

    /** Transformed class must still produce the same result as the sequential body. */
    @Test
    void rewrittenOrchestratorIsFunctionallyEquivalent() throws Exception {
        String className = "app.ForkJoinExec"; // unique to avoid duplicate loads
        String internal = className.replace('.', '/');
        byte[] original = generateForkJoinClass(internal);
        JsonObject metadata = buildForkJoinMetadata(internal);
        byte[] transformed = applyPass(original, metadata);

        Class<?> clazz = new ByteLoader().define(className, transformed);
        Method process = clazz.getMethod("process", int.class);

        // Expected = sequential composition of the same stage functions.
        int input = 42;
        int parsed = parse(input);
        int enhanced = enhance(parsed);
        int detected = detect(parsed);
        int expected = compose(enhanced, detected);

        Object actualObj = process.invoke(null, input);
        int actual = (Integer) actualObj;
        assertEquals(expected, actual,
                "pipeline rewrite must preserve semantic equivalence with sequential form");
    }

    /** Non-pipeline methods in the same class must remain untouched. */
    @Test
    void nonPipelineMethodLeftAlone() {
        String className = "app/ForkJoin";
        byte[] original = generateForkJoinClass(className);
        JsonObject metadata = buildForkJoinMetadata(className);
        byte[] transformed = applyPass(original, metadata);

        ClassNode cn = toClassNode(transformed);
        MethodNode other = findMethod(cn, "unrelated");
        assertNotNull(other);
        // unrelated must not have any CompletableFuture references.
        assertEquals(0, countMethodInsn(other, INVOKESTATIC, CF, "supplyAsync"));
        assertEquals(0, countOpcodes(other, INVOKEDYNAMIC));
    }

    /** Pass must no-op when no logic block carries isPipeline=true. */
    @Test
    void noPipelineMetadataLeavesClassUntouched() {
        String className = "app/NotPipe";
        byte[] original = generateForkJoinClass(className);
        JsonObject metadata = new JsonObject();
        // logicBlocks without isPipeline=true → pass still sees the block but
        // skips it. Downstream PassPipeline.hasAnyPipelineLogicBlock() keeps
        // the pass itself from running at all, but the pass must be safe if it
        // does get invoked on such metadata.
        JsonObject lb = new JsonObject();
        JsonObject block = new JsonObject();
        block.addProperty("qualifiedName", "app::process");
        block.addProperty("simpleName", "process");
        block.add("calledFunctions", new JsonArray());
        block.add("stages", new JsonArray());
        block.addProperty("isPipeline", false);
        lb.add("app::process", block);
        metadata.add("logicBlocks", lb);

        byte[] transformed = applyPass(original, metadata);
        ClassNode cn = toClassNode(transformed);
        MethodNode mn = findMethod(cn, "process");
        assertNotNull(mn);
        assertEquals(0, countMethodInsn(mn, INVOKESTATIC, CF, "supplyAsync"),
                "no pipeline → no rewrite");
    }

    // -----------------------------------------------------------------
    // Fixture class generation — hand-rolled to stay independent of javac.
    // -----------------------------------------------------------------

    private static int parse(int input)       { int r = input; for (int i = 0; i < 8; i++) r = r * 31 + i; return r; }
    private static int enhance(int data)      { int r = data;  for (int i = 0; i < 8; i++) r = r * 37 + i; return r; }
    private static int detect(int data)       { int r = data;  for (int i = 0; i < 8; i++) r = r * 41 + i; return r; }
    private static int compose(int a, int b)  { return a ^ b; }

    private byte[] generateForkJoinClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        // Same body shapes as the benchmark's stage fns; trimmed inner loops for speed.
        emitStage(cw, internalName, "parse",   31);
        emitStage(cw, internalName, "enhance", 37);
        emitStage(cw, internalName, "detect",  41);
        emitCompose(cw, internalName);

        // Orchestrator: sequential form — pipeline pass will replace the body.
        emitOrchestrator(cw, internalName);

        // Unrelated method used to confirm the pass doesn't touch non-pipelines.
        {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "unrelated",
                    "(I)I", null, null);
            mv.visitCode();
            mv.visitVarInsn(ILOAD, 0);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        // default ctor
        {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private void emitStage(ClassWriter cw, String owner, String name, int mul) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(I)I", null, null);
        mv.visitCode();
        // int r = input; for (int i = 0; i < 8; i++) r = r * mul + i; return r;
        mv.visitVarInsn(ILOAD, 0);
        mv.visitVarInsn(ISTORE, 1); // r
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 2); // i
        Label loopHead = new Label();
        Label loopEnd  = new Label();
        mv.visitLabel(loopHead);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitIntInsn(BIPUSH, 8);
        mv.visitJumpInsn(IF_ICMPGE, loopEnd);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitIntInsn(BIPUSH, mul);
        mv.visitInsn(IMUL);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ISTORE, 1);
        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(GOTO, loopHead);
        mv.visitLabel(loopEnd);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();
    }

    private void emitCompose(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "compose",
                "(II)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ILOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitInsn(IXOR);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private void emitOrchestrator(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "process",
                "(I)I", null, null);
        mv.visitCode();
        // int parsed = parse(input);
        mv.visitVarInsn(ILOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, owner, "parse", "(I)I", false);
        mv.visitVarInsn(ISTORE, 1);
        // int enhanced = enhance(parsed);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, owner, "enhance", "(I)I", false);
        mv.visitVarInsn(ISTORE, 2);
        // int detected = detect(parsed);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, owner, "detect", "(I)I", false);
        mv.visitVarInsn(ISTORE, 3);
        // return compose(enhanced, detected);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, owner, "compose", "(II)I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 4);
        mv.visitEnd();
    }

    private JsonObject buildForkJoinMetadata(String classInternal) {
        // qualifiedName for a .topo `namespace app` declaration on class app/X is `app::process`.
        int slash = classInternal.lastIndexOf('/');
        String ns = slash > 0 ? classInternal.substring(0, slash).replace('/', ':') : "";
        ns = ns.replace(":", "::"); // single colons from replace (edge case: package depth 1)
        String qname = ns.isEmpty() ? "process" : ns + "::process";

        JsonObject md = new JsonObject();
        JsonObject logicBlocks = new JsonObject();

        JsonObject block = new JsonObject();
        block.addProperty("qualifiedName", qname);
        block.addProperty("simpleName", "process");
        block.add("calledFunctions", new JsonArray());
        block.add("stages", new JsonArray());
        block.addProperty("isPipeline", true);

        JsonArray edges = new JsonArray();
        edges.add(edge("parse", "enhance", false, null));
        edges.add(edge("parse", "detect", false, null));
        edges.add(edge("enhance", "compose", false, null));
        edges.add(edge("detect", "compose", false, null));
        edges.add(edge("compose", "Int", true, "Int"));
        block.add("edges", edges);

        JsonObject pa = new JsonObject();
        JsonObject stages = new JsonObject();
        stages.addProperty("parse", 0);
        stages.addProperty("enhance", 1);
        stages.addProperty("detect", 1);
        stages.addProperty("compose", 2);
        pa.add("stages", stages);
        JsonArray sources = new JsonArray();
        sources.add("parse");
        pa.add("sourceNodes", sources);
        pa.addProperty("terminalNode", "compose");
        pa.addProperty("terminalType", "Int");
        pa.add("demand", new JsonObject());
        block.add("pipelineAnalysis", pa);

        logicBlocks.add(qname, block);
        md.add("logicBlocks", logicBlocks);
        return md;
    }

    private JsonObject edge(String src, String tgt, boolean terminal, String terminalType) {
        JsonObject e = new JsonObject();
        e.addProperty("source", src);
        e.addProperty("target", tgt);
        if (terminal) e.addProperty("isTerminal", true);
        if (terminalType != null) e.addProperty("terminalType", terminalType);
        return e;
    }

    // -----------------------------------------------------------------
    // Pass driver & inspection helpers.
    // -----------------------------------------------------------------

    private byte[] applyPass(byte[] classBytes, JsonObject metadata) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        PipelinePass pass = new PipelinePass(new JsonObject(), metadata);
        ClassVisitor visitor = pass.createVisitor(writer);
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private ClassNode toClassNode(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private MethodNode findMethod(ClassNode cn, String name) {
        for (MethodNode mn : cn.methods) if (mn.name.equals(name)) return mn;
        return null;
    }

    private long countOpcodes(MethodNode mn, int opcode) {
        long n = 0;
        for (AbstractInsnNode in : mn.instructions) if (in.getOpcode() == opcode) n++;
        return n;
    }

    private long countMethodInsn(MethodNode mn, int opcode, String owner, String name) {
        long n = 0;
        for (AbstractInsnNode in : mn.instructions) {
            if (in instanceof MethodInsnNode mi
                    && mi.getOpcode() == opcode
                    && mi.owner.equals(owner)
                    && mi.name.equals(name)) n++;
        }
        return n;
    }

    private List<String> findBridges(ClassNode cn, String prefix) {
        List<String> out = new ArrayList<>();
        for (MethodNode mn : cn.methods) if (mn.name.startsWith(prefix)) out.add(mn.name);
        return out;
    }

    /** Minimal ClassLoader that publishes a byte[] as a class. */
    private static final class ByteLoader extends ClassLoader {
        Class<?> define(String fqn, byte[] bytes) {
            return defineClass(fqn, bytes, 0, bytes.length);
        }
    }
}
