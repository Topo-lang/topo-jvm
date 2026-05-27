package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Unit tests for {@link ParallelPass}.
 *
 * Uses the ASM Tree API (ClassNode/MethodNode) to inspect the transformed bytecode
 * and verify that eligible void static calls are wrapped in spawn/awaitAllVoid,
 * while non-eligible calls and non-parallel methods remain untouched.
 */
class ParallelPassTest {

    @Test
    void voidStaticCallsWrapped() {
        String className = "app/Main";
        String methodName = "run";
        String[] targets = {"taskA", "taskB", "taskC"};

        byte[] original = generateClassWithStaticCalls(className, methodName, targets);
        JsonObject metadata = buildParallelMetadata(className, methodName, targets);
        byte[] transformed = applyPass(original, metadata);

        ClassNode cn = toClassNode(transformed);
        MethodNode mn = findMethod(cn, methodName);
        assertNotNull(mn, "Method '" + methodName + "' should exist after transform");

        // Count INVOKEDYNAMIC instructions (one per original static call)
        long indyCount = mn.instructions.toArray().length == 0 ? 0 :
                countOpcodes(mn, INVOKEDYNAMIC);
        assertEquals(targets.length, indyCount,
                "Each void static call should be replaced with an INVOKEDYNAMIC (lambda)");

        // Verify Parallel.spawn calls
        long spawnCount = countMethodInsn(mn, INVOKESTATIC, "dev/topo/Parallel", "spawn");
        assertEquals(targets.length, spawnCount,
                "Each lambda should be passed to Parallel.spawn()");

        // Verify awaitAllVoid before RETURN
        long awaitCount = countMethodInsn(mn, INVOKESTATIC, "dev/topo/Parallel", "awaitAllVoid");
        assertTrue(awaitCount >= 1,
                "awaitAllVoid should be called before RETURN");

        // The original INVOKESTATIC calls to taskA/taskB/taskC should be gone
        for (String target : targets) {
            long directCalls = countMethodInsn(mn, INVOKESTATIC, className, target);
            assertEquals(0, directCalls,
                    "Direct INVOKESTATIC to " + target + " should be replaced");
        }
    }

    /** Each wrapped spawn site must also inject a
     *  dev.topo.PassEvents.emitParallelSpawn() call so the per-Pass JFR
     *  profile event (topo.pass.ParallelPass) is recorded. */
    @Test
    void spawnSitesInjectPassEvent() {
        String className = "app/Main";
        String methodName = "run";
        String[] targets = {"taskA", "taskB", "taskC"};

        byte[] original = generateClassWithStaticCalls(className, methodName, targets);
        JsonObject metadata = buildParallelMetadata(className, methodName, targets);
        byte[] transformed = applyPass(original, metadata);

        ClassNode cn = toClassNode(transformed);
        MethodNode mn = findMethod(cn, methodName);
        assertNotNull(mn);

        long passEventCount = countMethodInsn(mn, INVOKESTATIC,
                "dev/topo/PassEvents", "emitParallelSpawn");
        assertEquals(targets.length, passEventCount,
                "Each parallel spawn site must inject one "
                + "PassEvents.emitParallelSpawn() call");
    }

    @Test
    void nonParallelMethodUntouched() {
        String className = "app/Main";
        String methodName = "run";
        String[] targets = {"taskA", "taskB", "taskC"};

        byte[] original = generateClassWithStaticCalls(className, methodName, targets);
        // Metadata has a logic block for a DIFFERENT method, not 'run'
        JsonObject metadata = buildParallelMetadata(className, "otherMethod",
                new String[]{"otherA", "otherB"});
        byte[] transformed = applyPass(original, metadata);

        ClassNode cn = toClassNode(transformed);
        MethodNode mn = findMethod(cn, methodName);
        assertNotNull(mn);

        // No INVOKEDYNAMIC should appear
        long indyCount = countOpcodes(mn, INVOKEDYNAMIC);
        assertEquals(0, indyCount, "Non-parallel method should have no INVOKEDYNAMIC");

        // Original static calls should remain
        for (String target : targets) {
            long directCalls = countMethodInsn(mn, INVOKESTATIC, className, target);
            assertEquals(1, directCalls,
                    "Original INVOKESTATIC to " + target + " should be preserved");
        }

        // No Parallel.spawn or awaitAllVoid
        long spawnCount = countMethodInsn(mn, INVOKESTATIC, "dev/topo/Parallel", "spawn");
        assertEquals(0, spawnCount);
        long awaitCount = countMethodInsn(mn, INVOKESTATIC, "dev/topo/Parallel", "awaitAllVoid");
        assertEquals(0, awaitCount);
    }

    @Test
    void nonVoidCallsPreserved() {
        String className = "app/Calc";
        String methodName = "compute";

        byte[] original = generateClassWithMixedCalls(className, methodName);
        // Logic block for "compute" with doWork and compute in same stage
        JsonObject metadata = buildParallelMetadata(className, methodName,
                new String[]{"doWork", "compute"});
        byte[] transformed = applyPass(original, metadata);

        ClassNode cn = toClassNode(transformed);
        MethodNode mn = findMethod(cn, methodName);
        assertNotNull(mn);

        // The void call ("doWork") should be wrapped -> INVOKEDYNAMIC
        long indyCount = countOpcodes(mn, INVOKEDYNAMIC);
        assertEquals(1, indyCount,
                "Only the void static call should become INVOKEDYNAMIC");

        // The non-void call ("getValue") should remain as INVOKESTATIC
        long getValueCalls = countMethodInsn(mn, INVOKESTATIC, className, "getValue");
        assertEquals(1, getValueCalls,
                "Non-void static call should remain as direct INVOKESTATIC");

        // The void call should NOT remain as direct INVOKESTATIC
        long doWorkCalls = countMethodInsn(mn, INVOKESTATIC, className, "doWork");
        assertEquals(0, doWorkCalls,
                "Void static call should be replaced");
    }

    // --- Helper methods ---

    /**
     * Generate a class with a void method that calls N void static methods.
     */
    private byte[] generateClassWithStaticCalls(String className, String methodName,
                                                 String[] callTargets) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC, className, null, "java/lang/Object", null);

        // Generate target static methods (void, no args)
        for (String target : callTargets) {
            var tmv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, target, "()V", null, null);
            tmv.visitCode();
            tmv.visitInsn(RETURN);
            tmv.visitMaxs(0, 0);
            tmv.visitEnd();
        }

        // Generate the calling method
        var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName, "()V", null, null);
        mv.visitCode();
        for (String target : callTargets) {
            mv.visitMethodInsn(INVOKESTATIC, className, target, "()V", false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();

        // Constructor
        emitDefaultConstructor(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate a class with a void method that calls both a void static and
     * a non-void static (returns int).
     */
    private byte[] generateClassWithMixedCalls(String className, String methodName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC, className, null, "java/lang/Object", null);

        // void doWork()
        var tw = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "doWork", "()V", null, null);
        tw.visitCode();
        tw.visitInsn(RETURN);
        tw.visitMaxs(0, 0);
        tw.visitEnd();

        // int getValue()
        var gv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getValue", "()I", null, null);
        gv.visitCode();
        gv.visitInsn(ICONST_0);
        gv.visitInsn(IRETURN);
        gv.visitMaxs(1, 0);
        gv.visitEnd();

        // void compute() — calls both
        var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, className, "doWork", "()V", false);
        mv.visitMethodInsn(INVOKESTATIC, className, "getValue", "()I", false);
        mv.visitInsn(POP); // discard int return
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();

        emitDefaultConstructor(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private void emitDefaultConstructor(ClassWriter cw) {
        var init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
    }

    private byte[] applyPass(byte[] classBytes, JsonObject metadata) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ParallelPass pass = new ParallelPass(new JsonObject(), metadata);
        ClassVisitor visitor = pass.createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private ClassNode toClassNode(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        return cn;
    }

    private MethodNode findMethod(ClassNode cn, String name) {
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(name)) return mn;
        }
        return null;
    }

    private long countOpcodes(MethodNode mn, int opcode) {
        long count = 0;
        for (var insn : mn.instructions) {
            if (insn.getOpcode() == opcode) count++;
        }
        return count;
    }

    private long countMethodInsn(MethodNode mn, int opcode, String owner, String name) {
        long count = 0;
        for (var insn : mn.instructions) {
            if (insn instanceof MethodInsnNode mi) {
                if (mi.getOpcode() == opcode && mi.owner.equals(owner) && mi.name.equals(name)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Build metadata with a logicBlock where the block's qualifiedName matches the
     * orchestrator method (className::methodName), and all targets share the same
     * stage (making them parallelizable).
     */
    private JsonObject buildParallelMetadata(String className, String methodName,
                                              String[] targets) {
        String namespace = className.replace("/", "::");
        String blockQName = namespace + "::" + methodName;
        JsonObject metadata = new JsonObject();

        JsonObject logicBlocks = new JsonObject();
        JsonObject block = new JsonObject();
        block.addProperty("qualifiedName", blockQName);
        block.addProperty("simpleName", methodName);
        JsonArray calledFunctions = new JsonArray();
        JsonArray stages = new JsonArray();
        for (String target : targets) {
            calledFunctions.add(target);
            stages.add(1); // All in same stage -> parallelizable
        }
        block.add("calledFunctions", calledFunctions);
        block.add("stages", stages);
        block.addProperty("isPipeline", false);
        block.add("edges", new JsonArray());
        logicBlocks.add(blockQName, block);
        metadata.add("logicBlocks", logicBlocks);

        return metadata;
    }
}
