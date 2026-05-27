package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReturnSpecializationPass — verifies dead PUTFIELD elimination
 * for unused return fields in multi-return methods.
 */
class ReturnSpecializationPassTest {

    private byte[] applyPass(byte[] input, JsonObject config, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ReturnSpecializationPass(config, metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private JsonObject defaultConfig() {
        var config = new JsonObject();
        config.addProperty("optLevel", 2);
        return config;
    }

    /**
     * Generate a Service class with a "compute" method that:
     * - Creates a ComputeResult
     * - Does PUTFIELD for "sum" (int)
     * - Does PUTFIELD for "debugInfo" (String)
     * - Returns the result object
     */
    private byte[] generateServiceClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Service", null,
                "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "compute", "()Lapp/ComputeResult;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "app/ComputeResult");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "app/ComputeResult", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        // result.sum = 42
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, 42);
        mv.visitFieldInsn(Opcodes.PUTFIELD, "app/ComputeResult", "sum", "I");

        // result.debugInfo = "debug"
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("debug");
        mv.visitFieldInsn(Opcodes.PUTFIELD, "app/ComputeResult", "debugInfo", "Ljava/lang/String;");

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate a Service class with a "compute" method that has a long field (category-2).
     * Fields: sum (int), timestamp (long)
     */
    private byte[] generateServiceClassWithLong() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Service", null,
                "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "compute", "()Lapp/ComputeResult;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "app/ComputeResult");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "app/ComputeResult", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        // result.sum = 42
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, 42);
        mv.visitFieldInsn(Opcodes.PUTFIELD, "app/ComputeResult", "sum", "I");

        // result.timestamp = 12345L (category-2)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn(12345L);
        mv.visitFieldInsn(Opcodes.PUTFIELD, "app/ComputeResult", "timestamp", "J");

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(4, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Build metadata for compute method with isMultiReturn and given returnParams + callSites. */
    private JsonObject buildMetadata(String[] returnParamNames, String[] usedReturns) {
        return buildMetadata(returnParamNames, usedReturns, null, null);
    }

    private JsonObject buildMetadata(String[] returnParamNames, String[] usedReturns,
                                      String style, String[] demandFields) {
        var metadata = new JsonObject();
        var functions = new JsonObject();

        var fn = new JsonObject();
        fn.addProperty("qualifiedName", "app::Service::compute");
        fn.addProperty("simpleName", "compute");
        fn.addProperty("visibility", "public");
        fn.addProperty("isMultiReturn", true);

        var rps = new JsonArray();
        for (String name : returnParamNames) {
            var rp = new JsonObject();
            var type = new JsonObject();
            type.addProperty("name", "int");
            rp.add("type", type);
            rp.addProperty("name", name);
            rps.add(rp);
        }
        fn.add("returnParams", rps);
        functions.add("app::Service::compute", fn);
        metadata.add("functions", functions);

        if (usedReturns != null) {
            var callSites = new JsonArray();
            var cs = new JsonObject();
            cs.addProperty("caller", "app::Main::run");
            cs.addProperty("callee", "app::Service::compute");
            if (style != null) cs.addProperty("style", style);
            var ur = new JsonArray();
            for (String r : usedReturns) ur.add(r);
            cs.add("usedReturns", ur);
            callSites.add(cs);
            metadata.add("callSites", callSites);
        }

        if (demandFields != null) {
            var logicBlocks = new JsonObject();
            var block = new JsonObject();
            block.addProperty("qualifiedName", "app::Main::run");
            var pa = new JsonObject();
            var demand = new JsonArray();
            for (String d : demandFields) demand.add(d);
            pa.add("demand", demand);
            block.add("pipelineAnalysis", pa);
            logicBlocks.add("app::Main::run", block);
            metadata.add("logicBlocks", logicBlocks);
        }

        return metadata;
    }

    /** Count PUTFIELD instructions for a specific field in a method. */
    private int countPutField(byte[] classBytes, String methodName, String fieldName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof FieldInsnNode fin) {
                    if (fin.getOpcode() == Opcodes.PUTFIELD && fin.name.equals(fieldName)) {
                        count++;
                    }
                }
            }
            return count;
        }
        return -1;
    }

    /** Count POP instructions in a method. */
    private int countPop(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn.getOpcode() == Opcodes.POP) count++;
            }
            return count;
        }
        return -1;
    }

    /** Count POP2 instructions in a method. */
    private int countPop2(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn.getOpcode() == Opcodes.POP2) count++;
            }
            return count;
        }
        return -1;
    }

    // --- Tests ---

    @Test
    void deadFieldEliminated() {
        byte[] input = generateServiceClass();
        var metadata = buildMetadata(
            new String[]{"sum", "debugInfo"},
            new String[]{"sum"}
        );

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertEquals(0, countPutField(output, "compute", "debugInfo"),
            "dead field PUTFIELD should be eliminated");
        assertEquals(1, countPutField(output, "compute", "sum"),
            "live field PUTFIELD should be preserved");
        assertTrue(countPop(output, "compute") >= 2,
            "POP instructions should replace dead PUTFIELD");
    }

    @Test
    void liveFieldPreserved() {
        byte[] input = generateServiceClass();
        var metadata = buildMetadata(
            new String[]{"sum", "debugInfo"},
            new String[]{"sum", "debugInfo"}
        );

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertEquals(1, countPutField(output, "compute", "sum"));
        assertEquals(1, countPutField(output, "compute", "debugInfo"));
    }

    @Test
    void category2Field() {
        byte[] input = generateServiceClassWithLong();
        var metadata = buildMetadata(
            new String[]{"sum", "timestamp"},
            new String[]{"sum"}
        );

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertEquals(0, countPutField(output, "compute", "timestamp"),
            "dead category-2 field should be eliminated");
        assertTrue(countPop2(output, "compute") >= 1,
            "POP2 should be used for category-2 field value");
    }

    @Test
    void allFieldsLive() {
        byte[] input = generateServiceClass();
        var metadata = buildMetadata(
            new String[]{"sum", "debugInfo"},
            new String[]{"sum", "debugInfo"}
        );

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertEquals(1, countPutField(output, "compute", "sum"));
        assertEquals(1, countPutField(output, "compute", "debugInfo"));
    }

    @Test
    void noCallSitesConservative() {
        byte[] input = generateServiceClass();
        var metadata = buildMetadata(
            new String[]{"sum", "debugInfo"},
            null  // null usedReturns means no callSites added
        );

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertEquals(1, countPutField(output, "compute", "sum"));
        assertEquals(1, countPutField(output, "compute", "debugInfo"));
    }

    @Test
    void demandSupplementsUsedReturns() {
        byte[] input = generateServiceClass();
        var metadata = buildMetadata(
            new String[]{"sum", "debugInfo"},
            new String[]{"sum"},
            "arrow",
            new String[]{"debugInfo"}
        );

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertEquals(1, countPutField(output, "compute", "sum"));
        assertEquals(1, countPutField(output, "compute", "debugInfo"));
    }

    @Test
    void nonMultiReturnIgnored() {
        byte[] input = generateServiceClass();

        var metadata = new JsonObject();
        var functions = new JsonObject();
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", "app::Service::compute");
        fn.addProperty("simpleName", "compute");
        fn.addProperty("visibility", "public");
        fn.addProperty("isMultiReturn", false);
        functions.add("app::Service::compute", fn);
        metadata.add("functions", functions);

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertEquals(1, countPutField(output, "compute", "sum"));
        assertEquals(1, countPutField(output, "compute", "debugInfo"));
    }

    @Test
    void fullStyleCallSitePreservesAllFields() {
        byte[] input = generateServiceClass();
        var metadata = buildMetadata(
            new String[]{"sum", "debugInfo"},
            new String[]{"sum"},
            "full",
            null
        );

        byte[] output = applyPass(input, defaultConfig(), metadata);

        assertEquals(1, countPutField(output, "compute", "sum"));
        assertEquals(1, countPutField(output, "compute", "debugInfo"));
    }

    @Test
    void emptyMetadata() {
        byte[] input = generateServiceClass();

        byte[] output = applyPass(input, defaultConfig(), new JsonObject());

        assertEquals(1, countPutField(output, "compute", "sum"));
        assertEquals(1, countPutField(output, "compute", "debugInfo"));
    }
}
