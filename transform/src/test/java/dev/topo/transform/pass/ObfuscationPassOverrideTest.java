package dev.topo.transform.pass;

import com.google.gson.JsonObject;
import dev.topo.transform.RealJavacFixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the override-chain preservation contract on
 * {@link ObfuscationPass} — when class {@code B extends A} both
 * declare {@code void foo()}, both must get the <em>same</em>
 * obfuscated name so the JVM still treats {@code B.foo} as an
 * override of {@code A.foo} and {@code b.foo()} dispatches to
 * {@code B}'s body.
 */
class ObfuscationPassOverrideTest {

    private JsonObject functionEntry(String qualifiedName, String visibility) {
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", qualifiedName);
        fn.addProperty("visibility", visibility);
        return fn;
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

    private byte[] applyPass(byte[] input, JsonObject metadata, String salt,
                             ClassHierarchy hierarchy) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ObfuscationPass(metadata, salt, hierarchy)
            .createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private Map<String, byte[]> compileBaseAndSub(String baseBody, String subBody)
            throws Exception {
        // Compiled together so Sub can see Base.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("app.Base",
            "package app;\npublic class Base {\n" + baseBody + "\n}\n");
        sources.put("app.Sub",
            "package app;\npublic class Sub extends Base {\n" + subBody + "\n}\n");
        return RealJavacFixture.compileAll(sources);
    }

    @Test
    void overrideChainPreservedBetweenBaseAndSubclass() throws Exception {
        // Base.foo and Sub.foo (Sub extends Base) must get the same
        // obfuscated name. Pre-fix they hashed under (Base, foo, …) vs
        // (Sub, foo, …) and the JVM treated Sub.foo as an unrelated
        // method that did NOT override Base.foo.
        Map<String, byte[]> compiled = compileBaseAndSub(
            "    public int foo() { return 1; }",
            "    @Override public int foo() { return 2; }");
        byte[] baseCls = compiled.get("app/Base");
        byte[] subCls = compiled.get("app/Sub");
        assertNotNull(baseCls, "compileAll must yield app/Base");
        assertNotNull(subCls, "compileAll must yield app/Sub");

        var metadata = new JsonObject();
        var functions = new JsonObject();
        // Both Base.foo and Sub.foo are non-public in .topo (we want
        // them renamed). The two-class hierarchy is what tests the
        // declaring-owner walk.
        functions.add("app::Base::foo",
            functionEntry("app::Base::foo", "internal"));
        functions.add("app::Sub::foo",
            functionEntry("app::Sub::foo", "internal"));
        metadata.add("functions", functions);

        ClassHierarchy hierarchy = ClassHierarchy.fromClassBytes(
            Arrays.asList(baseCls, subCls));

        byte[] baseOut = applyPass(baseCls, metadata, "salt", hierarchy);
        byte[] subOut = applyPass(subCls, metadata, "salt", hierarchy);

        Set<String> baseNames = getMethodNames(baseOut);
        Set<String> subNames = getMethodNames(subOut);
        baseNames.remove("<init>");
        subNames.remove("<init>");

        // Each class has exactly one user method.
        assertEquals(1, baseNames.size(), "base must keep one method: " + baseNames);
        assertEquals(1, subNames.size(), "sub must keep one method: " + subNames);

        String baseRenamed = baseNames.iterator().next();
        String subRenamed = subNames.iterator().next();
        assertTrue(baseRenamed.startsWith("_t"),
            "base method must be renamed under obfuscation: " + baseRenamed);
        assertEquals(baseRenamed, subRenamed,
            "override must hash to the same name as the base — otherwise "
            + "the JVM no longer treats Sub.foo as an override of Base.foo");
    }

    @Test
    void runtimeDispatchStillResolvesToSubclassBody() throws Exception {
        // Defines a small Base/Sub pair, obfuscates both, loads them in
        // a fresh ClassLoader, and verifies `((Base) sub).foo()`
        // returns Sub's value — proving the override link survives the
        // rename end-to-end.
        Map<String, byte[]> compiled = compileBaseAndSub(
            "    public int foo() { return 11; }",
            "    @Override public int foo() { return 22; }");
        byte[] baseCls = compiled.get("app/Base");
        byte[] subCls = compiled.get("app/Sub");

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Base::foo",
            functionEntry("app::Base::foo", "internal"));
        functions.add("app::Sub::foo",
            functionEntry("app::Sub::foo", "internal"));
        metadata.add("functions", functions);

        ClassHierarchy hierarchy = ClassHierarchy.fromClassBytes(
            Arrays.asList(baseCls, subCls));

        byte[] baseOut = applyPass(baseCls, metadata, "runtime-salt", hierarchy);
        byte[] subOut = applyPass(subCls, metadata, "runtime-salt", hierarchy);

        // Load both classes from byte arrays. Parent-first lookup means
        // Sub's superclass `app.Base` resolves to the obfuscated Base.
        ClassLoader cl = new ClassLoader(ClassLoader.getSystemClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes;
                if ("app.Base".equals(name)) bytes = baseOut;
                else if ("app.Sub".equals(name)) bytes = subOut;
                else throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Class<?> baseLoaded = Class.forName("app.Base", true, cl);
        Class<?> subLoaded = Class.forName("app.Sub", true, cl);

        // The original method name `foo` is now renamed; find the
        // single declared (non-<init>) method on each class.
        Method baseMethod = sole(baseLoaded, "Base");
        Method subMethod = sole(subLoaded, "Sub");
        assertEquals(baseMethod.getName(), subMethod.getName(),
            "post-rename method names must match for the override link to hold");

        Object subInstance = subLoaded.getDeclaredConstructor().newInstance();
        Object viaSub = subMethod.invoke(subInstance);
        // Invoke through the Base reflection handle to force virtual
        // dispatch through the Base slot.
        Object viaBaseSlot = baseMethod.invoke(subInstance);

        assertEquals(22, viaSub, "Sub.foo() must return Sub's value");
        assertEquals(22, viaBaseSlot,
            "calling foo() via the Base method handle on a Sub instance must "
            + "dispatch to Sub's body — override chain broken otherwise");
    }

    private Method sole(Class<?> cls, String label) {
        List<Method> kept = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals("<init>")) continue;
            kept.add(m);
        }
        assertEquals(1, kept.size(),
            label + " must declare exactly one non-<init> method; got " + kept);
        Method m = kept.get(0);
        m.setAccessible(true);
        return m;
    }

    @Test
    void privateMethodsKeepLiteralOwnerHash() throws Exception {
        // Private methods do not participate in override chains
        // (JVMS §5.4.5). Even when Base and Sub both declare a
        // same-signature private method, they are unrelated slots —
        // the obfuscator must hash each on its own owner. The
        // declaring-owner walk skips private methods (they're absent
        // from the overridable set), so this is the boundary check
        // that proves the walk doesn't over-eagerly collapse
        // private-method names.
        Map<String, byte[]> compiled = compileBaseAndSub(
            "    private int foo() { return 1; }",
            "    private int foo() { return 2; }"); // independent private slots
        byte[] baseCls = compiled.get("app/Base");
        byte[] subCls = compiled.get("app/Sub");

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Base::foo",
            functionEntry("app::Base::foo", "private"));
        functions.add("app::Sub::foo",
            functionEntry("app::Sub::foo", "private"));
        metadata.add("functions", functions);

        ClassHierarchy hierarchy = ClassHierarchy.fromClassBytes(
            Arrays.asList(baseCls, subCls));

        byte[] baseOut = applyPass(baseCls, metadata, "salt-x", hierarchy);
        byte[] subOut = applyPass(subCls, metadata, "salt-x", hierarchy);

        Set<String> baseNames = getMethodNames(baseOut);
        Set<String> subNames = getMethodNames(subOut);
        baseNames.remove("<init>");
        subNames.remove("<init>");

        assertEquals(1, baseNames.size(), "base methods: " + baseNames);
        assertEquals(1, subNames.size(), "sub methods: " + subNames);

        String baseRenamed = baseNames.iterator().next();
        String subRenamed = subNames.iterator().next();
        assertNotEquals(baseRenamed, subRenamed,
            "private Base.foo and Sub.foo are independent slots and must "
            + "NOT share a renamed name — the JVM dispatches private "
            + "methods statically per JVMS §5.4.5");
    }
}
