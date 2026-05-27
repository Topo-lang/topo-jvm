package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Tests for {@link StageReorderPass} — verifies that method call sequences
 * are reordered to match declared stage ordering, and that data dependencies
 * prevent unsafe reordering.
 */
class StageReorderPassTest {

    private static final String CLASS_NAME = "app/Service";

    /**
     * Calls stage2 then stage1, metadata says stage1=1 and stage2=2.
     * After transformation, stage1 should come before stage2.
     */
    @Test
    void wrongOrderReordered() {
        byte[] input = generateVoidCallClass(CLASS_NAME, "run",
                new String[]{"stage2", "stage1"});

        JsonObject metadata = buildMetadata(
                entry("stage1", 1),
                entry("stage2", 2)
        );

        byte[] output = transform(input, metadata);

        List<String> callOrder = extractCallOrder(output, "run");
        assertEquals(List.of("stage1", "stage2"), callOrder,
                "Calls should be reordered so stage1 (stage=1) precedes stage2 (stage=2)");
    }

    /**
     * Calls stage1 then stage2 — already in correct order.
     * Transformation should leave the order unchanged.
     */
    @Test
    void correctOrderPreserved() {
        byte[] input = generateVoidCallClass(CLASS_NAME, "run",
                new String[]{"stage1", "stage2"});

        JsonObject metadata = buildMetadata(
                entry("stage1", 1),
                entry("stage2", 2)
        );

        byte[] output = transform(input, metadata);

        List<String> callOrder = extractCallOrder(output, "run");
        assertEquals(List.of("stage1", "stage2"), callOrder,
                "Already-correct order should be preserved");
    }

    /**
     * stage2 returns an int that stage1 consumes — a data dependency.
     * The pass must not reorder because stage2 is not void and the
     * ISTORE/ILOAD between them breaks the reorderable group.
     */
    @Test
    void dataDependencyPreserved() {
        byte[] input = generateDataDependencyClass(CLASS_NAME, "run");

        JsonObject metadata = buildMetadata(
                entry("stage1", 1),
                entry("stage2", 2)
        );

        byte[] output = transform(input, metadata);

        List<String> callOrder = extractCallOrder(output, "run");
        assertEquals(List.of("stage2", "stage1"), callOrder,
                "Data dependency must prevent reordering — stage2 feeds stage1");
    }

    /**
     * Three stages in reverse order: stage3, stage2, stage1.
     * Should be reordered to stage1, stage2, stage3.
     */
    @Test
    void threeStagesReordered() {
        byte[] input = generateVoidCallClass(CLASS_NAME, "run",
                new String[]{"stage3", "stage2", "stage1"});

        JsonObject metadata = buildMetadata(
                entry("stage1", 1),
                entry("stage2", 2),
                entry("stage3", 3)
        );

        byte[] output = transform(input, metadata);

        List<String> callOrder = extractCallOrder(output, "run");
        assertEquals(List.of("stage1", "stage2", "stage3"), callOrder);
    }

    /**
     * Calls with no stage metadata should pass through unchanged.
     */
    @Test
    void noMetadataPassesThrough() {
        byte[] input = generateVoidCallClass(CLASS_NAME, "run",
                new String[]{"alpha", "beta"});

        JsonObject metadata = buildMetadata(); // empty logicBlocks

        byte[] output = transform(input, metadata);

        List<String> callOrder = extractCallOrder(output, "run");
        assertEquals(List.of("alpha", "beta"), callOrder,
                "Without stage metadata, call order must be unchanged");
    }

    // ---- Helpers ----

    private byte[] transform(byte[] input, JsonObject metadata) {
        JsonObject config = new JsonObject();
        StageReorderPass pass = new StageReorderPass(config, metadata);

        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = pass.createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * Extracts the ordered list of method call names from the named method.
     */
    private List<String> extractCallOrder(byte[] bytecode, String methodName) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytecode).accept(cn, 0);

        MethodNode method = cn.methods.stream()
                .filter(m -> m.name.equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Method not found: " + methodName));

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode min) {
                // Skip Object.<init> calls
                if (!"<init>".equals(min.name)) {
                    calls.add(min.name);
                }
            }
        }
        return calls;
    }

    /**
     * Generates a class with void static target methods and a calling method
     * that invokes them in the given order.
     */
    private byte[] generateVoidCallClass(String className, String callerName, String[] calls) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC, className, null, "java/lang/Object", null);

        // Generate static void target methods
        Set<String> generated = new HashSet<>();
        for (String call : calls) {
            if (generated.add(call)) {
                MethodVisitor tmv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, call, "()V", null, null);
                tmv.visitCode();
                tmv.visitInsn(RETURN);
                tmv.visitMaxs(0, 0);
                tmv.visitEnd();
            }
        }

        // Generate caller method
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, callerName, "()V", null, null);
        mv.visitCode();
        for (String call : calls) {
            mv.visitMethodInsn(INVOKESTATIC, className, call, "()V", false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // Constructor
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates a class where stage2() returns int, and stage1(int) consumes it.
     * The calling method does:
     *   int tmp = stage2();    // INVOKESTATIC ()I + ISTORE
     *   stage1(tmp);           // ILOAD + INVOKESTATIC (I)V
     *
     * The store/load between the calls creates a data dependency that must
     * prevent reordering.
     */
    private byte[] generateDataDependencyClass(String className, String callerName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC, className, null, "java/lang/Object", null);

        // stage2: returns int
        MethodVisitor s2 = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "stage2", "()I", null, null);
        s2.visitCode();
        s2.visitInsn(ICONST_1);
        s2.visitInsn(IRETURN);
        s2.visitMaxs(1, 0);
        s2.visitEnd();

        // stage1: takes int, returns void
        MethodVisitor s1 = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "stage1", "(I)V", null, null);
        s1.visitCode();
        s1.visitInsn(RETURN);
        s1.visitMaxs(0, 1);
        s1.visitEnd();

        // Caller: int tmp = stage2(); stage1(tmp);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, callerName, "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, className, "stage2", "()I", false);
        mv.visitVarInsn(ISTORE, 0);
        mv.visitVarInsn(ILOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, className, "stage1", "(I)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // Constructor
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Build metadata with logicBlocks containing calledFunctions and stages.
     * Uses "app::Service" as namespace (matching CLASS_NAME).
     */
    @SafeVarargs
    private JsonObject buildMetadata(Map.Entry<String, Integer>... entries) {
        JsonObject metadata = new JsonObject();
        JsonObject logicBlocks = new JsonObject();

        if (entries.length > 0) {
            JsonObject block = new JsonObject();
            String blockQName = "app::Service::_logic";
            block.addProperty("qualifiedName", blockQName);
            block.addProperty("simpleName", "_logic");
            JsonArray calledFunctions = new JsonArray();
            JsonArray stages = new JsonArray();
            for (var e : entries) {
                calledFunctions.add(e.getKey());
                stages.add(e.getValue());
            }
            block.add("calledFunctions", calledFunctions);
            block.add("stages", stages);
            block.addProperty("isPipeline", false);
            block.add("edges", new JsonArray());
            logicBlocks.add(blockQName, block);
        }

        metadata.add("logicBlocks", logicBlocks);
        return metadata;
    }

    private Map.Entry<String, Integer> entry(String name, int stage) {
        return Map.entry(name, stage);
    }
}
