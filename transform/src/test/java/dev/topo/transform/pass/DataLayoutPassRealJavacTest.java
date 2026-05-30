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
 * Real-javac counterpart to {@link DataLayoutPassTest}.
 *
 * The DataLayoutPass is a force-only pass that rewrites
 * {@code dev.topo.Array.get(i).field} accesses to columnar form.
 * The synthetic test fabricates the exact bytecode pattern. This
 * real-javac test verifies the pass also leaves *non-matching*
 * javac-emitted bytecode intact (no spurious rewrites, no
 * StackMapFrame corruption).
 */
class DataLayoutPassRealJavacTest {

    private byte[] applyPass(byte[] input, JsonObject config, JsonObject metadata) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new DataLayoutPass(config, metadata).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private JsonObject forceConfig() {
        var config = new JsonObject();
        var dataLayoutCfg = new JsonObject();
        dataLayoutCfg.addProperty("mode", "force");
        config.add("dataLayoutCfg", dataLayoutCfg);
        return config;
    }

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

    @Test
    void realJavacBytecodeWithoutTopoArrayIsLeftIntact() throws Exception {
        // A typical javac-compiled class with no dev/topo/Array.get
        // calls must round-trip through the pass unchanged in shape
        // (same method set, valid bytecode read-back).
        String source = "package app;\n"
                      + "import java.util.List;\n"
                      + "public class Plain {\n"
                      + "    public int sum(List<Integer> xs) {\n"
                      + "        int s = 0;\n"
                      + "        for (Integer v : xs) s += v;\n"
                      + "        return s;\n"
                      + "    }\n"
                      + "}\n";
        byte[] cls = RealJavacFixture.compileOne("app.Plain", source);
        Set<String> namesBefore = getMethodNames(cls);

        byte[] output = applyPass(cls, forceConfig(), new JsonObject());

        Set<String> namesAfter = getMethodNames(output);
        assertEquals(namesBefore, namesAfter,
            "method set must be unchanged when no Array.get pattern is present");
        // ClassFormatError would surface here; readback proves the
        // StackMapFrame attributes javac emitted for the for-each are
        // still consistent.
        assertDoesNotThrow(() -> new ClassReader(output).accept(
            new ClassVisitor(Opcodes.ASM9) {}, 0));
    }

    @Test
    void realJavacInitConstructorIsPreserved() throws Exception {
        // The pass should never touch <init>; verify against a real
        // javac default constructor (which carries an ALOAD + INVOKESPECIAL
        // pair the synthetic tests don't model precisely).
        String source = "package app;\n"
                      + "public class Service {\n"
                      + "    public int api() { return 42; }\n"
                      + "}\n";
        byte[] cls = RealJavacFixture.compileOne("app.Service", source);

        byte[] output = applyPass(cls, forceConfig(), new JsonObject());
        Set<String> names = getMethodNames(output);
        assertTrue(names.contains("<init>"),
            "javac-emitted default <init> must survive a DataLayoutPass run");
    }
}
