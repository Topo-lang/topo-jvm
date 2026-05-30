package dev.topo.transform.pass;

import com.google.gson.JsonObject;
import dev.topo.transform.RealJavacFixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-javac counterpart to {@link ObfuscationPassTest}.
 *
 * Verifies the obfuscator does not corrupt javac-emitted attribute
 * tables (e.g. {@code <init>} default constructor, line-number
 * tables) and that overloaded methods — which carry javac-emitted
 * synthetic bridges in certain shapes — survive renaming.
 */
class ObfuscationPassRealJavacTest {

    private Set<String> getMethodNames(byte[] classBytes) {
        Set<String> names = new HashSet<>();
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                names.add(name);
                return null;
            }
        }, 0);
        return names;
    }

    private byte[] applyPass(byte[] input, JsonObject metadata, String salt) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ObfuscationPass(metadata, salt).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private JsonObject functionEntry(String qualifiedName, String visibility) {
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", qualifiedName);
        fn.addProperty("visibility", visibility);
        return fn;
    }

    @Test
    void realJavacInitIsPreservedAcrossObfuscation() throws Exception {
        String source = "package app;\n"
                      + "public class Service {\n"
                      + "    private void helper() {}\n"
                      + "    public  void api() {}\n"
                      + "}\n";
        byte[] cls = RealJavacFixture.compileOne("app.Service", source);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Service::api",     functionEntry("app::Service::api",     "public"));
        functions.add("app::Service::helper",  functionEntry("app::Service::helper",  "private"));
        metadata.add("functions", functions);

        byte[] output = applyPass(cls, metadata, "salt");
        var names = getMethodNames(output);

        assertTrue(names.contains("<init>"),
            "javac-emitted default constructor must survive obfuscation");
        assertTrue(names.contains("api"),
            "public method must keep its name");
        assertFalse(names.contains("helper"),
            "private method should be renamed");
    }

    @Test
    void overloadedJavacMethodsStayDistinct() throws Exception {
        // Real javac may attach bridge methods or generic erasure
        // signatures to overloads; the obfuscator's MethodKey must
        // still keep the two overloads distinct after rename.
        String source = "package app;\n"
                      + "public class Service {\n"
                      + "    private void helper(int x)  {}\n"
                      + "    private void helper(long x) {}\n"
                      + "}\n";
        byte[] cls = RealJavacFixture.compileOne("app.Service", source);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Service::helper",
            functionEntry("app::Service::helper", "private"));
        metadata.add("functions", functions);

        byte[] output = applyPass(cls, metadata, "salt");

        // Read back must not throw (no duplicate-method ClassFormatError)
        assertDoesNotThrow(() -> new ClassReader(output));

        // Count obfuscated methods: must still be exactly 2 (plus <init>).
        Set<String> names = getMethodNames(output);
        names.remove("<init>");
        assertEquals(2, names.size(),
            "both javac-emitted overloads must survive with distinct names; got " + names);
    }

    @Test
    void realJavacBytecodeRoundTripsAfterObfuscation() throws Exception {
        // Multi-statement method bodies carry StackMapFrame tables that
        // javac emits but the synthetic ClassWriter tests don't trigger.
        String source = "package app;\n"
                      + "public class Service {\n"
                      + "    private int compute(int x) {\n"
                      + "        int y = 0;\n"
                      + "        for (int i = 0; i < x; i++) y += i;\n"
                      + "        return y;\n"
                      + "    }\n"
                      + "}\n";
        byte[] cls = RealJavacFixture.compileOne("app.Service", source);

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Service::compute",
            functionEntry("app::Service::compute", "private"));
        metadata.add("functions", functions);

        byte[] output = applyPass(cls, metadata, "salt");
        assertDoesNotThrow(() -> new ClassReader(output).accept(
            new ClassVisitor(Opcodes.ASM9) {}, 0));
    }
}
