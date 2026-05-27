package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StaticPromotionPass — verifies instance-to-static promotion
 * with descriptor extension and call site rewriting.
 */
class StaticPromotionPassTest {

    private byte[] applyPass(byte[] input, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new StaticPromotionPass(metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private byte[] generateClass(String className, String methodName, int methodAccess, String desc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null,
                 "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        var mv = cw.visitMethod(methodAccess, methodName, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, computeMaxLocals(methodAccess, desc));
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate a class with a callee method and a caller that invokes it via INVOKEVIRTUAL.
     */
    private byte[] generateClassWithCall(String className, String callerName,
                                          String calleeName, String calleeDesc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null,
                 "java/lang/Object", null);

        // constructor
        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // callee method (instance, will be promoted)
        var callee = cw.visitMethod(Opcodes.ACC_PUBLIC, calleeName, calleeDesc, null, null);
        callee.visitCode();
        callee.visitInsn(Opcodes.RETURN);
        callee.visitMaxs(0, computeMaxLocals(Opcodes.ACC_PUBLIC, calleeDesc));
        callee.visitEnd();

        // caller method that invokes callee via INVOKEVIRTUAL
        var caller = cw.visitMethod(Opcodes.ACC_PUBLIC, callerName, "()V", null, null);
        caller.visitCode();
        caller.visitVarInsn(Opcodes.ALOAD, 0); // push this (receiver for INVOKEVIRTUAL)
        // Push dummy args matching calleeDesc
        pushDummyArgs(caller, calleeDesc);
        caller.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, calleeName, calleeDesc, false);
        caller.visitInsn(Opcodes.RETURN);
        int stackSize = 1 + countDescriptorSlots(calleeDesc);
        caller.visitMaxs(stackSize, 1);
        caller.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Push dummy constant values matching the parameter types of a descriptor. */
    private void pushDummyArgs(MethodVisitor mv, String desc) {
        // Parse params between '(' and ')'
        int i = 1; // skip '('
        while (i < desc.length() && desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            switch (c) {
                case 'I', 'B', 'S', 'Z', 'C' -> {
                    mv.visitInsn(Opcodes.ICONST_0);
                    i++;
                }
                case 'J' -> {
                    mv.visitInsn(Opcodes.LCONST_0);
                    i++;
                }
                case 'F' -> {
                    mv.visitInsn(Opcodes.FCONST_0);
                    i++;
                }
                case 'D' -> {
                    mv.visitInsn(Opcodes.DCONST_0);
                    i++;
                }
                case 'L' -> {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    i = desc.indexOf(';', i) + 1;
                }
                case '[' -> {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    // skip array dimensions and element type
                    while (i < desc.length() && desc.charAt(i) == '[') i++;
                    if (i < desc.length() && desc.charAt(i) == 'L') {
                        i = desc.indexOf(';', i) + 1;
                    } else {
                        i++;
                    }
                }
                default -> i++;
            }
        }
    }

    /** Count the number of JVM stack slots consumed by descriptor parameters. */
    private int countDescriptorSlots(String desc) {
        int slots = 0;
        int i = 1;
        while (i < desc.length() && desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            switch (c) {
                case 'J', 'D' -> { slots += 2; i++; }
                case 'L' -> { slots++; i = desc.indexOf(';', i) + 1; }
                case '[' -> {
                    slots++;
                    while (i < desc.length() && desc.charAt(i) == '[') i++;
                    if (i < desc.length() && desc.charAt(i) == 'L') {
                        i = desc.indexOf(';', i) + 1;
                    } else {
                        i++;
                    }
                }
                default -> { slots++; i++; }
            }
        }
        return slots;
    }

    /** Compute a reasonable maxLocals value for a method. */
    private int computeMaxLocals(int access, String desc) {
        int locals = ((access & Opcodes.ACC_STATIC) != 0) ? 0 : 1;
        locals += countDescriptorSlots(desc);
        return locals;
    }

    private int getMethodAccess(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        int[] result = {-1};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (name.equals(methodName)) result[0] = access;
                return null;
            }
        }, 0);
        return result[0];
    }

    private String getMethodDescriptor(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        String[] result = {null};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (name.equals(methodName)) result[0] = descriptor;
                return null;
            }
        }, 0);
        return result[0];
    }

    /** Find the opcode of the first call to the named method in any method body. */
    private int getCallSiteOpcode(byte[] classBytes, String targetMethodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);

        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals(targetMethodName)) {
                    return call.getOpcode();
                }
            }
        }
        return -1;
    }

    /** Find the descriptor at the first call site to the named method. */
    private String getCallSiteDescriptor(byte[] classBytes, String targetMethodName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);

        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals(targetMethodName)) {
                    return call.desc;
                }
            }
        }
        return null;
    }

    private JsonObject buildMetadata(String qualifiedName, boolean isStatic) {
        var metadata = new JsonObject();
        var functions = new JsonObject();
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", qualifiedName);
        fn.addProperty("simpleName", qualifiedName.substring(qualifiedName.lastIndexOf("::") + 2));
        fn.addProperty("visibility", "internal");
        fn.addProperty("isStatic", isStatic);
        fn.addProperty("returnType", "void");
        fn.add("params", new JsonArray());
        functions.add(qualifiedName, fn);
        metadata.add("functions", functions);
        return metadata;
    }

    // --- Tests ---

    @Test
    void basicPromotion() {
        byte[] input = generateClass("app/Service", "compute", Opcodes.ACC_PUBLIC, "(I)V");
        var metadata = buildMetadata("app::Service::compute", true);

        byte[] output = applyPass(input, metadata);

        int access = getMethodAccess(output, "compute");
        assertTrue((access & Opcodes.ACC_STATIC) != 0, "promoted method should have ACC_STATIC");

        String desc = getMethodDescriptor(output, "compute");
        assertEquals("(Lapp/Service;I)V", desc, "descriptor should be extended with owner type");
    }

    @Test
    void callSiteRewrite() {
        byte[] input = generateClassWithCall("app/Worker", "caller", "helper", "(I)V");
        var metadata = buildMetadata("app::Worker::helper", true);

        byte[] output = applyPass(input, metadata);

        int opcode = getCallSiteOpcode(output, "helper");
        assertEquals(Opcodes.INVOKESTATIC, opcode, "call site should be rewritten to INVOKESTATIC");

        String desc = getCallSiteDescriptor(output, "helper");
        assertEquals("(Lapp/Worker;I)V", desc, "call site descriptor should be extended");
    }

    @Test
    void withParameters() {
        byte[] input = generateClass("app/Service", "process", Opcodes.ACC_PUBLIC, "(IJ)V");
        var metadata = buildMetadata("app::Service::process", true);

        byte[] output = applyPass(input, metadata);

        String desc = getMethodDescriptor(output, "process");
        assertEquals("(Lapp/Service;IJ)V", desc,
                     "multi-param descriptor should extend correctly");
    }

    @Test
    void constructorSkipped() {
        var metadata = buildMetadata("app::Service::<init>", true);
        byte[] input = generateClass("app/Service", "compute", Opcodes.ACC_PUBLIC, "(I)V");

        byte[] output = applyPass(input, metadata);

        int access = getMethodAccess(output, "<init>");
        assertFalse((access & Opcodes.ACC_STATIC) != 0,
                    "<init> should never be promoted to static");
    }

    @Test
    void alreadyStaticUnchanged() {
        byte[] input = generateClass("app/Service", "utility", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "(I)V");
        var metadata = buildMetadata("app::Service::utility", true);

        byte[] output = applyPass(input, metadata);

        String desc = getMethodDescriptor(output, "utility");
        assertEquals("(I)V", desc, "already static method descriptor should remain unchanged");
    }

    @Test
    void noMetadataNoChange() {
        byte[] input = generateClass("app/Service", "process", Opcodes.ACC_PUBLIC, "(I)V");
        var metadata = new JsonObject();

        byte[] output = applyPass(input, metadata);

        int access = getMethodAccess(output, "process");
        assertFalse((access & Opcodes.ACC_STATIC) != 0, "no metadata should mean no promotion");

        String desc = getMethodDescriptor(output, "process");
        assertEquals("(I)V", desc, "descriptor should remain unchanged");
    }

    @Test
    void publicMethodNotPromoted() {
        byte[] input = generateClass("app/Service", "process", Opcodes.ACC_PUBLIC, "(I)V");
        var metadata = buildMetadata("app::Service::process", false);

        byte[] output = applyPass(input, metadata);

        int access = getMethodAccess(output, "process");
        assertFalse((access & Opcodes.ACC_STATIC) != 0,
                    "isStatic=false should not add ACC_STATIC");

        String desc = getMethodDescriptor(output, "process");
        assertEquals("(I)V", desc, "descriptor should remain unchanged");
    }
}
