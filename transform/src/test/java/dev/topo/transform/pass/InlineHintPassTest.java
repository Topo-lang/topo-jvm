package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InlineHintPass — verifies ACC_FINAL addition for JIT inline hints.
 */
class InlineHintPassTest {

    private byte[] applyPass(byte[] input, JsonObject config, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new InlineHintPass(config, metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private byte[] generateClass(String className, String methodName, int methodAccess) {
        return generateClass(className, methodName, methodAccess, Opcodes.ACC_PUBLIC);
    }

    private byte[] generateClass(String className, String methodName, int methodAccess,
                                  int classAccess) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, classAccess, className, null,
                 "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        var mv = cw.visitMethod(methodAccess, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Generate a class with two methods. */
    private byte[] generateClassTwoMethods(String className,
                                            String method1, int access1,
                                            String method2, int access2) {
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

        var mv1 = cw.visitMethod(access1, method1, "()V", null, null);
        mv1.visitCode();
        mv1.visitInsn(Opcodes.RETURN);
        mv1.visitMaxs(0, 1);
        mv1.visitEnd();

        var mv2 = cw.visitMethod(access2, method2, "()V", null, null);
        mv2.visitCode();
        mv2.visitInsn(Opcodes.RETURN);
        mv2.visitMaxs(0, 1);
        mv2.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
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

    private int getClassAccess(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        int[] result = {-1};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                             String superName, String[] interfaces) {
                result[0] = access;
            }
        }, 0);
        return result[0];
    }

    private JsonObject defaultConfig() {
        var config = new JsonObject();
        config.addProperty("optLevel", 2);
        return config;
    }

    private JsonObject buildMetadata(String qualifiedName, String visibility) {
        var metadata = new JsonObject();
        var functions = new JsonObject();
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", qualifiedName);
        fn.addProperty("simpleName", qualifiedName.substring(qualifiedName.lastIndexOf("::") + 2));
        fn.addProperty("visibility", visibility);
        fn.addProperty("returnType", "void");
        fn.add("params", new JsonArray());
        functions.add(qualifiedName, fn);
        metadata.add("functions", functions);
        return metadata;
    }

    private JsonObject buildMetadataTwoFunctions(String qn1, String vis1,
                                                   String qn2, String vis2) {
        var metadata = new JsonObject();
        var functions = new JsonObject();

        var fn1 = new JsonObject();
        fn1.addProperty("qualifiedName", qn1);
        fn1.addProperty("simpleName", qn1.substring(qn1.lastIndexOf("::") + 2));
        fn1.addProperty("visibility", vis1);
        fn1.addProperty("returnType", "void");
        fn1.add("params", new JsonArray());
        functions.add(qn1, fn1);

        var fn2 = new JsonObject();
        fn2.addProperty("qualifiedName", qn2);
        fn2.addProperty("simpleName", qn2.substring(qn2.lastIndexOf("::") + 2));
        fn2.addProperty("visibility", vis2);
        fn2.addProperty("returnType", "void");
        fn2.add("params", new JsonArray());
        functions.add(qn2, fn2);

        metadata.add("functions", functions);
        return metadata;
    }

    // --- Method-level tests ---

    @Test
    void internalMethodGetsFinal() {
        byte[] input = generateClass("app/Service", "helper", Opcodes.ACC_PUBLIC);
        var metadata = buildMetadata("app::Service::helper", "internal");

        byte[] output = applyPass(input, defaultConfig(), metadata);
        int access = getMethodAccess(output, "helper");

        assertTrue((access & Opcodes.ACC_FINAL) != 0, "internal method should get ACC_FINAL");
    }

    @Test
    void privateMethodGetsFinal() {
        byte[] input = generateClass("app/Service", "compute", Opcodes.ACC_PRIVATE);
        var metadata = buildMetadata("app::Service::compute", "private");

        byte[] output = applyPass(input, defaultConfig(), metadata);
        int access = getMethodAccess(output, "compute");

        assertTrue((access & Opcodes.ACC_FINAL) != 0, "private method should get ACC_FINAL");
    }

    @Test
    void publicMethodUnchanged() {
        byte[] input = generateClass("app/Service", "process", Opcodes.ACC_PUBLIC);
        var metadata = buildMetadata("app::Service::process", "public");

        byte[] output = applyPass(input, defaultConfig(), metadata);
        int access = getMethodAccess(output, "process");

        assertFalse((access & Opcodes.ACC_FINAL) != 0, "public method should not get ACC_FINAL");
    }

    @Test
    void protectedMethodUnchanged() {
        byte[] input = generateClass("app/Service", "hook", Opcodes.ACC_PROTECTED);
        var metadata = buildMetadata("app::Service::hook", "protected");

        byte[] output = applyPass(input, defaultConfig(), metadata);
        int access = getMethodAccess(output, "hook");

        assertFalse((access & Opcodes.ACC_FINAL) != 0, "protected method should not get ACC_FINAL");
    }

    @Test
    void constructorNeverFinal() {
        // Build metadata that references <init> as internal
        var metadata = buildMetadata("app::Service::<init>", "internal");

        byte[] input = generateClass("app/Service", "helper", Opcodes.ACC_PUBLIC);
        byte[] output = applyPass(input, defaultConfig(), metadata);
        int access = getMethodAccess(output, "<init>");

        assertFalse((access & Opcodes.ACC_FINAL) != 0, "<init> should never get ACC_FINAL");
    }

    @Test
    void abstractMethodNotFinal() {
        // Generate an abstract class with an abstract method
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                 "app/Base", null, "java/lang/Object", null);

        var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // Abstract method — no code body
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                       "run", "()V", null, null).visitEnd();
        cw.visitEnd();

        byte[] input = cw.toByteArray();
        var metadata = buildMetadata("app::Base::run", "internal");
        byte[] output = applyPass(input, defaultConfig(), metadata);
        int access = getMethodAccess(output, "run");

        assertFalse((access & Opcodes.ACC_FINAL) != 0,
                    "abstract method should not get ACC_FINAL");
    }

    // --- Class-level tests ---

    @Test
    void classLevelFinal_allInternal() {
        byte[] input = generateClassTwoMethods("app/Helper",
                "compute", Opcodes.ACC_PUBLIC,
                "reset", Opcodes.ACC_PUBLIC);
        var metadata = buildMetadataTwoFunctions(
                "app::Helper::compute", "internal",
                "app::Helper::reset", "private");

        byte[] output = applyPass(input, defaultConfig(), metadata);
        int classAccess = getClassAccess(output);

        assertTrue((classAccess & Opcodes.ACC_FINAL) != 0,
                   "class with all internal/private methods should get ACC_FINAL");
    }

    @Test
    void classLevelNotFinal_mixedVisibility() {
        byte[] input = generateClassTwoMethods("app/Service",
                "process", Opcodes.ACC_PUBLIC,
                "helper", Opcodes.ACC_PUBLIC);
        var metadata = buildMetadataTwoFunctions(
                "app::Service::process", "public",
                "app::Service::helper", "internal");

        byte[] output = applyPass(input, defaultConfig(), metadata);
        int classAccess = getClassAccess(output);

        assertFalse((classAccess & Opcodes.ACC_FINAL) != 0,
                    "class with mixed visibility should not get ACC_FINAL");
    }

    @Test
    void noMetadataNoChange() {
        byte[] input = generateClass("app/Service", "process", Opcodes.ACC_PUBLIC);
        var metadata = new JsonObject();

        byte[] output = applyPass(input, defaultConfig(), metadata);
        int access = getMethodAccess(output, "process");

        assertFalse((access & Opcodes.ACC_FINAL) != 0, "no metadata should mean no changes");
    }
}
