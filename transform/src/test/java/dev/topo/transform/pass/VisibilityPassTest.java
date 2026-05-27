package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VisibilityPass — verifies access flag modification.
 */
class VisibilityPassTest {

    /** Apply VisibilityPass to a generated class and return the transformed bytes. */
    private byte[] applyPass(byte[] input, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new VisibilityPass(metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /** Generate a class with one method at the given access level. */
    private byte[] generateClass(String className, String methodName, int access) {
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

        var mv = cw.visitMethod(access, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Read back the access flags of a named method from class bytes. */
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

    /** Build metadata with a single function entry in the Object format. */
    private JsonObject buildFunctionsMetadata(String qualifiedName, String visibility) {
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

    @Test
    void publicToPrivate() {
        byte[] input = generateClass("app/Service", "process", Opcodes.ACC_PUBLIC);
        var metadata = buildFunctionsMetadata("app::Service::process", "private");

        byte[] output = applyPass(input, metadata);
        int access = getMethodAccess(output, "process");

        assertTrue((access & Opcodes.ACC_PRIVATE) != 0);
        assertFalse((access & Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void privateToPublic() {
        byte[] input = generateClass("app/Service", "process", Opcodes.ACC_PRIVATE);
        var metadata = buildFunctionsMetadata("app::Service::process", "public");

        byte[] output = applyPass(input, metadata);
        int access = getMethodAccess(output, "process");

        assertTrue((access & Opcodes.ACC_PUBLIC) != 0);
        assertFalse((access & Opcodes.ACC_PRIVATE) != 0);
    }

    @Test
    void toInternal() {
        byte[] input = generateClass("app/Service", "helper", Opcodes.ACC_PUBLIC);
        var metadata = buildFunctionsMetadata("app::Service::helper", "internal");

        byte[] output = applyPass(input, metadata);
        int access = getMethodAccess(output, "helper");

        // package-private: no public/private/protected flags
        assertEquals(0, access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED));
    }

    @Test
    void unmatchedMethodUntouched() {
        byte[] input = generateClass("app/Service", "unrelated", Opcodes.ACC_PUBLIC);
        var metadata = buildFunctionsMetadata("app::Service::other", "private");

        byte[] output = applyPass(input, metadata);
        int access = getMethodAccess(output, "unrelated");

        assertTrue((access & Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void noMetadataNoChange() {
        byte[] input = generateClass("app/Service", "process", Opcodes.ACC_PUBLIC);
        var metadata = new JsonObject();

        byte[] output = applyPass(input, metadata);
        int access = getMethodAccess(output, "process");

        assertTrue((access & Opcodes.ACC_PUBLIC) != 0);
    }
}
