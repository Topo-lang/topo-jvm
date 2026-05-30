package dev.topo.transform.pass;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.topo.transform.RealJavacFixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-javac counterpart to {@link VisibilityPassTest}.
 *
 * The companion class fabricates minimal bytecode via {@link ClassWriter};
 * this class compiles a small Java source through the JDK's
 * {@link javax.tools.JavaCompiler} and runs the same pass on the
 * resulting real {@code .class} bytes. That exercises the parts of the
 * pass surface that synthetic bytecode tends to mask: synthetic
 * constructor flags, real local-variable tables, real
 * {@code StackMapFrame} attributes, and the JDK class-file conventions
 * for newer Java features.
 */
class VisibilityPassRealJavacTest {

    private byte[] applyPass(byte[] input, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new VisibilityPass(metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
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

    private JsonObject functionEntry(String qualifiedName, String visibility) {
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", qualifiedName);
        int last = qualifiedName.lastIndexOf("::");
        fn.addProperty("simpleName", last >= 0 ? qualifiedName.substring(last + 2) : qualifiedName);
        fn.addProperty("visibility", visibility);
        fn.addProperty("returnType", "void");
        fn.add("params", new JsonArray());
        return fn;
    }

    private JsonObject singleFunctionMetadata(String qualifiedName, String visibility) {
        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add(qualifiedName, functionEntry(qualifiedName, visibility));
        metadata.add("functions", functions);
        return metadata;
    }

    @Test
    void publicJavacMethodIsDemotedToPrivate() throws Exception {
        String source = "package app;\n"
                      + "public class Service {\n"
                      + "    public void process() {}\n"
                      + "}\n";
        byte[] cls = RealJavacFixture.compileOne("app.Service", source);

        // Sanity-check the real-javac output: the public method must
        // start as ACC_PUBLIC. If javac ever stops emitting that we
        // would otherwise silently pass on the wrong baseline.
        assertTrue((getMethodAccess(cls, "process") & Opcodes.ACC_PUBLIC) != 0,
            "javac baseline: process should start as public");

        byte[] output = applyPass(cls, singleFunctionMetadata("app::Service::process", "private"));
        int access = getMethodAccess(output, "process");

        assertTrue((access & Opcodes.ACC_PRIVATE) != 0,
            "post-pass: process should be private");
        assertFalse((access & Opcodes.ACC_PUBLIC) != 0,
            "post-pass: process must no longer be public");
    }

    @Test
    void javacConstructorIsLeftAlone() throws Exception {
        // The pass should not touch <init>; verify on real javac output
        // where the implicit default constructor is emitted by javac.
        String source = "package app;\n"
                      + "public class Service {\n"
                      + "    public void helper() {}\n"
                      + "}\n";
        byte[] cls = RealJavacFixture.compileOne("app.Service", source);

        byte[] output = applyPass(cls, singleFunctionMetadata("app::Service::helper", "private"));

        // <init> from javac is public; assert it survives at the same access.
        int initAccess = getMethodAccess(output, "<init>");
        assertTrue((initAccess & Opcodes.ACC_PUBLIC) != 0,
            "javac-emitted <init> must not be demoted by the pass");
    }

    @Test
    void realJavacBytecodeRoundTripsWithoutCorruption() throws Exception {
        // The pass returns bytes via ClassWriter; if a downstream invariant
        // (StackMapFrame, attributes) gets dropped, ASM's ClassReader
        // refuses to read it back. This pins that the pass does not
        // corrupt real-javac bytecode.
        String source = "package app;\n"
                      + "public class Service {\n"
                      + "    public int compute(int x) {\n"
                      + "        if (x > 0) return x * 2;\n"
                      + "        else return -x;\n"
                      + "    }\n"
                      + "}\n";
        byte[] cls = RealJavacFixture.compileOne("app.Service", source);

        byte[] output = applyPass(cls, singleFunctionMetadata("app::Service::compute", "private"));
        // Reading back is itself the invariant: ClassFormatError surfaces here.
        assertDoesNotThrow(() -> new ClassReader(output).accept(
            new ClassVisitor(Opcodes.ASM9) {}, 0));
    }
}
