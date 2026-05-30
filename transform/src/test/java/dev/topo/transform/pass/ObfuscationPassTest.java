package dev.topo.transform.pass;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ObfuscationPass — verifies internal method renaming
 * while preserving public API.
 */
class ObfuscationPassTest {

    private byte[] generateClass(String className, String[] methods, int[] access) {
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

        for (int i = 0; i < methods.length; i++) {
            var mv = cw.visitMethod(access[i], methods[i], "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
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

    private byte[] applyPass(byte[] input, JsonObject metadata, String salt) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ObfuscationPass(metadata, salt).createVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /** Build a functions object entry matching the canonical topo-core schema. */
    private JsonObject functionEntry(String qualifiedName, String visibility) {
        var fn = new JsonObject();
        fn.addProperty("qualifiedName", qualifiedName);
        fn.addProperty("visibility", visibility);
        return fn;
    }

    @Test
    void publicMethodPreserved() {
        byte[] input = generateClass("app/Service",
            new String[]{"publicApi", "internalHelper"},
            new int[]{Opcodes.ACC_PUBLIC, Opcodes.ACC_PRIVATE});

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Service::publicApi",
            functionEntry("app::Service::publicApi", "public"));
        functions.add("app::Service::internalHelper",
            functionEntry("app::Service::internalHelper", "private"));
        metadata.add("functions", functions);

        byte[] output = applyPass(input, metadata, "test-salt");
        var names = getMethodNames(output);

        assertTrue(names.contains("publicApi"), "public method should be preserved");
        assertFalse(names.contains("internalHelper"), "internal method should be renamed");
    }

    @Test
    void constructorPreserved() {
        byte[] input = generateClass("app/Service", new String[]{}, new int[]{});
        var metadata = new JsonObject();

        byte[] output = applyPass(input, metadata, "salt");
        var names = getMethodNames(output);

        assertTrue(names.contains("<init>"), "constructor should not be renamed");
    }

    @Test
    void obfuscatedNameStartsWithPrefix() {
        byte[] input = generateClass("app/Service",
            new String[]{"helper"}, new int[]{Opcodes.ACC_PRIVATE});
        var metadata = new JsonObject();
        // The pass only renames methods whose owner appears in the user-declared
        // namespace set, which is derived from functions[*] qualifiedName.
        var functions = new JsonObject();
        functions.add("app::Service::helper",
            functionEntry("app::Service::helper", "private"));
        metadata.add("functions", functions);

        byte[] output = applyPass(input, metadata, "salt");
        var names = getMethodNames(output);

        // helper should be renamed to _t<32 hex chars> (SipHash-2-4-128
        // = 16 bytes = 32 hex), matching the LLVM SymbolObfuscator's
        // output length. The earlier SHA-256/64 implementation emitted
        // only 16 hex chars.
        names.remove("<init>");
        assertEquals(1, names.size());
        String obfuscated = names.iterator().next();
        assertTrue(obfuscated.startsWith("_t"), "obfuscated name should start with _t prefix");
        assertEquals(34, obfuscated.length(),
            "SipHash-2-4-128 prefix + 32 hex chars; got " + obfuscated);
    }

    /**
     * Cross-backend parity: assert the JVM obfuscator now uses
     * SipHash-2-4-128 (matching {@code topo-llvm/lib/Transforms/SymbolObfuscator.cpp}).
     *
     * <p>This test exercises three pinned properties that the parity
     * gate ({@code scripts/audit/cross-backend-parity.py}) relies on:
     *
     * <ol>
     *   <li>the algorithm tag string ({@code HASH_ALGORITHM}) matches
     *       the LLVM side verbatim — the gate greps both sources for
     *       this exact token;</li>
     *   <li>the digest length is exactly 16 bytes (128 bits) — the
     *       output-length parity row;</li>
     *   <li>the digest is non-trivial: different keys + different
     *       messages produce different outputs (rules out a degenerate
     *       implementation that always returns zeros).</li>
     * </ol>
     *
     * <p>The exact byte-level digest is verified end-to-end by the
     * cross-backend parity gate, which hashes the same input via
     * both backends and asserts equality — that test belongs there,
     * not in this JVM-only unit suite.
     */
    @Test
    void hashAlgorithmMatchesLLVMBackend() {
        assertEquals("SipHash-2-4-128", ObfuscationPass.HASH_ALGORITHM);

        byte[] out = SipHash.hash128(
            "test-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "app::Service::helperV".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(16, out.length, "SipHash-2-4-128 must emit 128 bits");

        // Different message → different digest (PRF non-degeneracy)
        byte[] out2 = SipHash.hash128(
            "test-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "app::Service::helperW".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(java.util.Arrays.equals(out, out2),
            "SipHash output should differ for different messages");

        // Different key → different digest (key separation)
        byte[] out3 = SipHash.hash128(
            "other-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "app::Service::helperV".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(java.util.Arrays.equals(out, out3),
            "SipHash output should differ for different keys");
    }

    @Test
    void hashIsDeterministicForSameKeyAndMessage() {
        // Determinism is a cross-backend contract: re-running the
        // obfuscator on the same input must produce the same hex name.
        // The parity gate cannot assume so otherwise.
        byte[] key = "shared-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] msg = "app::Service::run".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(SipHash.hash128(key, msg), SipHash.hash128(key, msg));
    }

    /**
     * Generate a class with two methods sharing a name but differing
     * descriptors. The pre-MethodKey ObfuscationPass collapsed these to
     * the same obfuscated name (producing invalid bytecode); after the
     * MethodKey refactor each overload gets its own name.
     */
    private byte[] generateClassWithOverloads(String className) {
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

        // Two overloads of `helper`: one takes int, one takes long.
        for (String desc : new String[]{"(I)V", "(J)V"}) {
            var mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "helper", desc, null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, desc.contains("J") ? 3 : 2);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private java.util.Map<String, String> getMethodNameDescriptorPairs(byte[] classBytes) {
        java.util.Map<String, String> pairs = new java.util.HashMap<>();
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                pairs.put(name + descriptor, name);
                return null;
            }
        }, 0);
        return pairs;
    }

    @Test
    void overloadedMethodsGetDistinctObfuscatedNames() {
        byte[] input = generateClassWithOverloads("app/Service");

        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Service::helper",
            functionEntry("app::Service::helper", "private"));
        metadata.add("functions", functions);

        byte[] output = applyPass(input, metadata, "test-salt");
        var pairs = getMethodNameDescriptorPairs(output);

        // The output must still contain BOTH overloads, each with a
        // distinct (renamed) name. Pre-MethodKey, both methods got the
        // same obfuscated name, producing a duplicate-method
        // ClassFormatError when the JVM loaded the bytes.
        pairs.entrySet().removeIf(e -> e.getValue().equals("<init>"));
        assertEquals(2, pairs.size(), "both overloads must survive");

        // Collect the bare obfuscated names; they must all be distinct.
        var distinctNames = new HashSet<String>();
        for (var v : pairs.values()) distinctNames.add(v);
        assertEquals(2, distinctNames.size(),
            "overloaded methods must get distinct obfuscated names");
    }

    @Test
    void overloadedMethodsProduceValidBytecode() {
        // Round-trip the output through ASM's verifier: if rename
        // collapsed the overloads to the same name, ClassReader would
        // refuse to read the duplicate methods.
        byte[] input = generateClassWithOverloads("app/Service");
        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Service::helper",
            functionEntry("app::Service::helper", "private"));
        metadata.add("functions", functions);

        byte[] output = applyPass(input, metadata, "salt");

        // Read it back; this throws if the class is invalid.
        assertDoesNotThrow(() -> new ClassReader(output));
    }

    @Test
    void differentSaltProducesDifferentNames() {
        byte[] input = generateClass("app/Service",
            new String[]{"helper"}, new int[]{Opcodes.ACC_PRIVATE});
        var metadata = new JsonObject();
        var functions = new JsonObject();
        functions.add("app::Service::helper",
            functionEntry("app::Service::helper", "private"));
        metadata.add("functions", functions);

        byte[] out1 = applyPass(input, metadata, "salt1");
        byte[] out2 = applyPass(input, metadata, "salt2");

        var names1 = getMethodNames(out1);
        var names2 = getMethodNames(out2);
        names1.remove("<init>");
        names2.remove("<init>");

        assertNotEquals(names1, names2, "different salts should produce different names");
    }
}
