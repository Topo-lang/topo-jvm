package topo.decompile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JVMLifter L2 structured control flow recovery.
 *
 * Each test generates bytecode via ASM, wraps it in a ClassNode/MethodNode,
 * and calls StructuredLifter directly to verify the output JSON contains
 * the expected IfStmt / ForStmt / WhileStmt nodes with populated bodies.
 */
class StructuredLifterTest {

    // -----------------------------------------------------------------------
    // Test: If/else recovery
    // -----------------------------------------------------------------------

    @Test
    void ifElseRecovery() {
        // Generate:
        //   int test(int x) {
        //     if (x > 0) { return 1; } else { return -1; }
        //   }
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestIfElse", null,
                "java/lang/Object", null);

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "(I)I", null, null);
        mv.visitCode();

        Label elseLabel = new Label();
        Label endLabel = new Label();

        // if (x > 0)
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFLE, elseLabel);

        // then: return 1
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        // else: return -1
        mv.visitLabel(elseLabel);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode classNode = toClassNode(cw);
        MethodNode methodNode = findMethod(classNode, "test");

        var lifter = new StructuredLifter(classNode, methodNode,
                new JsonObject(), Collections.emptyMap());
        JsonArray result = lifter.lift();

        // Should contain an IfStmt
        JsonObject ifStmt = findStmtByKind(result, "if");
        assertNotNull(ifStmt, "Structured lift should produce an IfStmt");
        assertNotNull(ifStmt.get("condition"), "IfStmt should have a condition");
        assertTrue(ifStmt.has("thenBody"), "IfStmt should have a thenBody");

        // The then-body should contain at least one statement (the return)
        JsonArray thenBody = ifStmt.getAsJsonArray("thenBody");
        assertFalse(thenBody.isEmpty(),
                "IfStmt thenBody should be populated with the return statement");
    }

    // -----------------------------------------------------------------------
    // Test: Counted loop (for-loop) recovery
    // -----------------------------------------------------------------------

    @Test
    void countedLoopRecovery() {
        // Generate:
        //   void test(int n) {
        //     for (int i = 0; i < n; i++) {
        //       // body: some work
        //     }
        //   }
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestForLoop", null,
                "java/lang/Object", null);

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "(I)V", null, null);
        mv.visitCode();

        Label loopStart = new Label();
        Label loopEnd = new Label();

        // int i = 0
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 1);

        // loop header: i < n ?
        mv.visitLabel(loopStart);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);

        // body: noop (just a local read to have something)
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.POP);

        // i++
        mv.visitIincInsn(1, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        // end
        mv.visitLabel(loopEnd);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode classNode = toClassNode(cw);
        MethodNode methodNode = findMethod(classNode, "test");

        var lifter = new StructuredLifter(classNode, methodNode,
                new JsonObject(), Collections.emptyMap());
        JsonArray result = lifter.lift();

        // Should produce a ForStmt due to IINC pattern
        JsonObject forStmt = findStmtByKind(result, "for");
        assertNotNull(forStmt, "Counted loop should produce a ForStmt");
        assertNotNull(forStmt.get("init"), "ForStmt should have init");
        assertNotNull(forStmt.get("condition"), "ForStmt should have condition");
        assertNotNull(forStmt.get("increment"), "ForStmt should have increment");
        assertTrue(forStmt.has("body"), "ForStmt should have a body");
    }

    // -----------------------------------------------------------------------
    // Test: While loop recovery (non-counted loop)
    // -----------------------------------------------------------------------

    @Test
    void whileLoopRecovery() {
        // Generate:
        //   void test(int[] arr) {
        //     int i = 0;
        //     while (arr[i] != 0) {
        //       i = arr[i]; // non-trivial increment -> not counted
        //     }
        //   }
        //
        // Simplified bytecode: a loop that loads, compares, and uses a
        // non-IINC step (IADD from array load).
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestWhileLoop", null,
                "java/lang/Object", null);

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "([I)V", null, null);
        mv.visitCode();

        Label loopStart = new Label();
        Label loopEnd = new Label();

        // int idx = 0
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 1);

        // loop header
        mv.visitLabel(loopStart);
        // arr[idx]
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.IALOAD);

        // if arr[idx] == 0, exit
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // body: idx = arr[idx]  (non-IINC step)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, 1);

        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        // end
        mv.visitLabel(loopEnd);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode classNode = toClassNode(cw);
        MethodNode methodNode = findMethod(classNode, "test");

        var lifter = new StructuredLifter(classNode, methodNode,
                new JsonObject(), Collections.emptyMap());
        JsonArray result = lifter.lift();

        // Should produce a WhileStmt (no IINC -> not a for-loop)
        JsonObject whileStmt = findStmtByKind(result, "while");
        assertNotNull(whileStmt, "Non-counted loop should produce a WhileStmt");
        assertNotNull(whileStmt.get("condition"), "WhileStmt should have a condition");
        assertTrue(whileStmt.has("body"), "WhileStmt should have a body");
        JsonArray body = whileStmt.getAsJsonArray("body");
        assertFalse(body.isEmpty(), "WhileStmt body should contain the assignment");
    }

    // -----------------------------------------------------------------------
    // Test: Direct level produces no structured control flow
    // -----------------------------------------------------------------------

    @Test
    void directLevelUsesNoStructuredRecovery() {
        // Same if/else bytecode as ifElseRecovery, but lifted via JVMLifter
        // at "direct" level. StructuredLifter is only called at "structured"
        // level, so here we verify the bytecode converter path produces flat
        // statement lists without IfStmt wrapping.
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestDirect", null,
                "java/lang/Object", null);

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "(I)I", null, null);
        mv.visitCode();

        Label elseLabel = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFLE, elseLabel);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(elseLabel);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode classNode = toClassNode(cw);
        MethodNode methodNode = findMethod(classNode, "test");

        // BytecodeConverter is the direct-level path (no structured recovery)
        var converter = new BytecodeConverter(classNode, methodNode,
                new JsonObject(), Collections.emptyMap());
        JsonArray result = converter.convert();

        // Direct level should NOT produce IfStmt nodes — it emits flat statements
        JsonObject ifStmt = findStmtByKind(result, "if");
        assertNull(ifStmt,
                "Direct-level lifting should not produce IfStmt (no CFG reconstruction)");

        // It should still produce some output (at minimum the return statements)
        assertFalse(result.isEmpty(), "Direct-level lifting should produce statements");
    }

    // -----------------------------------------------------------------------
    // Test: try / catch(SpecificException e) recovery
    // -----------------------------------------------------------------------

    @Test
    void tryCatchSpecificExceptionRecovery() {
        // void test() {
        //   try { foo(); }
        //   catch (IllegalStateException e) { bar(); }
        // }
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestTryCatch", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "()V", null, null);
        mv.visitCode();

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label end = new Label();

        mv.visitTryCatchBlock(tryStart, tryEnd, handler,
                "java/lang/IllegalStateException");

        mv.visitLabel(tryStart);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryCatch", "foo", "()V", false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, end);

        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 0); // store caught exception
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryCatch", "bar", "()V", false);

        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);

        // declare the referenced static methods so the class is well-formed
        addStatic(cw, "foo");
        addStatic(cw, "bar");

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode classNode = toClassNode(cw);
        MethodNode methodNode = findMethod(classNode, "test");

        var lifter = new StructuredLifter(classNode, methodNode,
                new JsonObject(), Collections.emptyMap());
        JsonArray result = lifter.lift();

        JsonObject tc = findStmtByKind(result, "trycatch");
        assertNotNull(tc, "Should recover a TryCatch statement");
        assertTrue(tc.has("tryBody"), "TryCatch should have a tryBody");
        assertFalse(tc.getAsJsonArray("tryBody").isEmpty(),
                "tryBody should contain the protected call");

        JsonArray catches = tc.getAsJsonArray("catchClauses");
        assertEquals(1, catches.size(), "Exactly one catch clause");
        JsonObject clause = catches.get(0).getAsJsonObject();
        assertEquals("e", clause.get("varName").getAsString(),
                "First catch var should be synthesized as 'e'");
        assertEquals("java.lang.IllegalStateException",
                joinNameParts(clause.getAsJsonObject("exceptionType")),
                "exceptionType should be the caught class qualified name");
        assertFalse(clause.getAsJsonArray("body").isEmpty(),
                "catch body should contain the handler call");
        assertFalse(tc.has("finallyBody"),
                "No finally expected for try/catch");
    }

    // -----------------------------------------------------------------------
    // Test: try / multi-catch recovery
    // -----------------------------------------------------------------------

    @Test
    void tryMultiCatchRecovery() {
        // try { foo(); }
        // catch (IllegalStateException e) { bar(); }
        // catch (IllegalArgumentException e) { baz(); }
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestMultiCatch", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "()V", null, null);
        mv.visitCode();

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label h1 = new Label();
        Label h2 = new Label();
        Label end = new Label();

        mv.visitTryCatchBlock(tryStart, tryEnd, h1, "java/lang/IllegalStateException");
        mv.visitTryCatchBlock(tryStart, tryEnd, h2, "java/lang/IllegalArgumentException");

        mv.visitLabel(tryStart);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestMultiCatch", "foo", "()V", false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, end);

        mv.visitLabel(h1);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestMultiCatch", "bar", "()V", false);
        mv.visitJumpInsn(Opcodes.GOTO, end);

        mv.visitLabel(h2);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestMultiCatch", "baz", "()V", false);

        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);

        addStatic(cw, "foo");
        addStatic(cw, "bar");
        addStatic(cw, "baz");

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode classNode = toClassNode(cw);
        MethodNode methodNode = findMethod(classNode, "test");

        var lifter = new StructuredLifter(classNode, methodNode,
                new JsonObject(), Collections.emptyMap());
        JsonArray result = lifter.lift();

        JsonObject tc = findStmtByKind(result, "trycatch");
        assertNotNull(tc, "Should recover a TryCatch statement");
        JsonArray catches = tc.getAsJsonArray("catchClauses");
        assertEquals(2, catches.size(), "Two catch clauses expected");
        assertEquals("java.lang.IllegalStateException",
                joinNameParts(catches.get(0).getAsJsonObject()
                        .getAsJsonObject("exceptionType")));
        assertEquals("java.lang.IllegalArgumentException",
                joinNameParts(catches.get(1).getAsJsonObject()
                        .getAsJsonObject("exceptionType")));
        assertEquals("e", catches.get(0).getAsJsonObject().get("varName").getAsString());
        assertEquals("e2", catches.get(1).getAsJsonObject().get("varName").getAsString());
    }

    // -----------------------------------------------------------------------
    // Test: try / catch / finally recovery
    // -----------------------------------------------------------------------

    @Test
    void tryCatchFinallyRecovery() {
        // try { foo(); } catch (RuntimeException e) { bar(); } finally { cleanup(); }
        // javac lowers this to: a typed catch handler + a synthetic
        // catch-all (type==null) handler that re-raises after running cleanup.
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestTryCatchFinally", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "()V", null, null);
        mv.visitCode();

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchH = new Label();
        Label finallyH = new Label();
        Label end = new Label();

        mv.visitTryCatchBlock(tryStart, tryEnd, catchH, "java/lang/RuntimeException");
        mv.visitTryCatchBlock(tryStart, tryEnd, finallyH, null); // finally / any

        mv.visitLabel(tryStart);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryCatchFinally", "foo", "()V", false);
        mv.visitLabel(tryEnd);
        // normal completion: run finally inline then jump to end
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryCatchFinally", "cleanup", "()V", false);
        mv.visitJumpInsn(Opcodes.GOTO, end);

        mv.visitLabel(catchH);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryCatchFinally", "bar", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryCatchFinally", "cleanup", "()V", false);
        mv.visitJumpInsn(Opcodes.GOTO, end);

        mv.visitLabel(finallyH);
        mv.visitVarInsn(Opcodes.ASTORE, 1); // store in-flight exception
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryCatchFinally", "cleanup", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ATHROW); // re-raise

        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);

        addStatic(cw, "foo");
        addStatic(cw, "bar");
        addStatic(cw, "cleanup");

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode classNode = toClassNode(cw);
        MethodNode methodNode = findMethod(classNode, "test");

        var lifter = new StructuredLifter(classNode, methodNode,
                new JsonObject(), Collections.emptyMap());
        JsonArray result = lifter.lift();

        JsonObject tc = findStmtByKind(result, "trycatch");
        assertNotNull(tc, "Should recover a TryCatch statement");
        JsonArray catches = tc.getAsJsonArray("catchClauses");
        assertEquals(1, catches.size(), "One typed catch clause");
        assertEquals("java.lang.RuntimeException",
                joinNameParts(catches.get(0).getAsJsonObject()
                        .getAsJsonObject("exceptionType")));
        assertTrue(tc.has("finallyBody"), "finallyBody must be present");
        JsonArray fin = tc.getAsJsonArray("finallyBody");
        assertFalse(fin.isEmpty(),
                "finallyBody should contain the cleanup call (re-raise stripped)");
    }

    // -----------------------------------------------------------------------
    // Test: try / finally (no catch) recovery
    // -----------------------------------------------------------------------

    @Test
    void tryFinallyNoCatchRecovery() {
        // try { foo(); } finally { cleanup(); }
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestTryFinally", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "()V", null, null);
        mv.visitCode();

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label finallyH = new Label();
        Label end = new Label();

        mv.visitTryCatchBlock(tryStart, tryEnd, finallyH, null);

        mv.visitLabel(tryStart);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryFinally", "foo", "()V", false);
        mv.visitLabel(tryEnd);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryFinally", "cleanup", "()V", false);
        mv.visitJumpInsn(Opcodes.GOTO, end);

        mv.visitLabel(finallyH);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestTryFinally", "cleanup", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ATHROW);

        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);

        addStatic(cw, "foo");
        addStatic(cw, "cleanup");

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode classNode = toClassNode(cw);
        MethodNode methodNode = findMethod(classNode, "test");

        var lifter = new StructuredLifter(classNode, methodNode,
                new JsonObject(), Collections.emptyMap());
        JsonArray result = lifter.lift();

        JsonObject tc = findStmtByKind(result, "trycatch");
        assertNotNull(tc, "Should recover a TryCatch statement");
        assertTrue(tc.getAsJsonArray("catchClauses").isEmpty(),
                "try/finally has no catch clauses");
        assertTrue(tc.has("finallyBody"), "finallyBody must be present");
        assertFalse(tc.getAsJsonArray("finallyBody").isEmpty(),
                "finallyBody should contain the cleanup call");
        assertFalse(tc.getAsJsonArray("tryBody").isEmpty(),
                "tryBody should contain the protected call");
    }

    // -----------------------------------------------------------------------
    // Test: nested try is conservatively refused (recorded unsupported)
    // -----------------------------------------------------------------------

    @Test
    void nestedTryFallsBackAndRecordsUnsupported() {
        // Two distinct protected ranges in one method -> refuse, record.
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestNested", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "()V", null, null);
        mv.visitCode();

        Label oS = new Label(), oE = new Label(), oH = new Label();
        Label iS = new Label(), iE = new Label(), iH = new Label();
        Label end = new Label();

        mv.visitTryCatchBlock(oS, oE, oH, "java/lang/RuntimeException");
        mv.visitTryCatchBlock(iS, iE, iH, "java/lang/IllegalStateException");

        mv.visitLabel(oS);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestNested", "foo", "()V", false);
        mv.visitLabel(iS);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestNested", "bar", "()V", false);
        mv.visitLabel(iE);
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(iH);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestNested", "baz", "()V", false);
        mv.visitLabel(oE);
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(oH);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "TestNested", "qux", "()V", false);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);

        addStatic(cw, "foo");
        addStatic(cw, "bar");
        addStatic(cw, "baz");
        addStatic(cw, "qux");

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode classNode = toClassNode(cw);
        MethodNode methodNode = findMethod(classNode, "test");

        var lifter = new StructuredLifter(classNode, methodNode,
                new JsonObject(), Collections.emptyMap());
        JsonArray result = lifter.lift();

        // Conservative: no (possibly-wrong) TryCatch emitted at top level,
        // and the construct is recorded as unsupported.
        assertNull(findStmtByKind(result, "trycatch"),
                "Nested/sequential try should not be force-structured");
        assertTrue(lifter.getUnsupportedConstructs().stream()
                        .anyMatch(s -> s.contains("nested-or-sequential")),
                "Nested try must be recorded as unsupported, got: "
                        + lifter.getUnsupportedConstructs());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void addStatic(ClassWriter cw, String name) {
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static String joinNameParts(JsonObject typeNode) {
        JsonArray parts = typeNode.getAsJsonArray("nameParts");
        var sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('.');
            sb.append(parts.get(i).getAsString());
        }
        return sb.toString();
    }

    private static ClassNode toClassNode(ClassWriter cw) {
        var classNode = new ClassNode();
        var reader = new ClassReader(cw.toByteArray());
        reader.accept(classNode, 0);
        return classNode;
    }

    private static MethodNode findMethod(ClassNode classNode, String name) {
        for (var m : classNode.methods) {
            if (m.name.equals(name)) return m;
        }
        throw new AssertionError("Method not found: " + name);
    }

    /**
     * Search a JsonArray of statements for the first one with the given "kind".
     * Returns null if not found.
     */
    private static JsonObject findStmtByKind(JsonArray stmts, String kind) {
        for (int i = 0; i < stmts.size(); i++) {
            var elem = stmts.get(i);
            if (!elem.isJsonObject()) continue;
            var obj = elem.getAsJsonObject();
            if (kind.equals(getKind(obj))) return obj;
            // Recurse into bodies
            JsonObject found = searchBodiesForKind(obj, kind);
            if (found != null) return found;
        }
        return null;
    }

    private static JsonObject searchBodiesForKind(JsonObject stmt, String kind) {
        for (String bodyField : new String[]{"thenBody", "elseBody", "body"}) {
            if (stmt.has(bodyField) && stmt.get(bodyField).isJsonArray()) {
                JsonObject found = findStmtByKind(stmt.getAsJsonArray(bodyField), kind);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String getKind(JsonObject obj) {
        return obj.has("kind") ? obj.get("kind").getAsString() : "";
    }
}
