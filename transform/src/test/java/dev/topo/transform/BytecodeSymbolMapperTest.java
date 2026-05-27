package dev.topo.transform;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeSymbolMapperTest {
    private final BytecodeSymbolMapper mapper = new BytecodeSymbolMapper();

    // --- toJvmClassName ---

    @Test
    void classNameFromQualifiedMethod() {
        assertEquals("app/Engine", mapper.toJvmClassName("app::Engine::process"));
    }

    @Test
    void classNameFromClassName() {
        // toJvmClassName always strips last :: segment (assumes it's a method)
        assertEquals("app", mapper.toJvmClassName("app::Engine"));
    }

    @Test
    void classNameSingleSegment() {
        assertEquals("Main", mapper.toJvmClassName("Main"));
    }

    @Test
    void classNameDeepNesting() {
        assertEquals("com/example/core/Engine",
            mapper.toJvmClassName("com::example::core::Engine::run"));
    }

    // --- extractMethodName ---

    @Test
    void extractMethodFromQualified() {
        assertEquals("process", mapper.extractMethodName("app::Engine::process"));
    }

    @Test
    void extractMethodNoNamespace() {
        assertEquals("main", mapper.extractMethodName("main"));
    }

    // --- toJvmTypeDescriptor ---

    @Test
    void primitiveTypes() {
        assertEquals("I", mapper.toJvmTypeDescriptor("int"));
        assertEquals("J", mapper.toJvmTypeDescriptor("long"));
        assertEquals("D", mapper.toJvmTypeDescriptor("double"));
        assertEquals("F", mapper.toJvmTypeDescriptor("float"));
        assertEquals("Z", mapper.toJvmTypeDescriptor("bool"));
        assertEquals("V", mapper.toJvmTypeDescriptor("void"));
        assertEquals("B", mapper.toJvmTypeDescriptor("byte"));
        assertEquals("S", mapper.toJvmTypeDescriptor("short"));
        assertEquals("C", mapper.toJvmTypeDescriptor("char"));
    }

    @Test
    void stringType() {
        assertEquals("Ljava/lang/String;", mapper.toJvmTypeDescriptor("string"));
        assertEquals("Ljava/lang/String;", mapper.toJvmTypeDescriptor("String"));
    }

    @Test
    void objectType() {
        assertEquals("Ljava/util/List;", mapper.toJvmTypeDescriptor("java.util.List"));
    }

    @Test
    void topoNamespaceObjectType() {
        assertEquals("Lapp/Engine;", mapper.toJvmTypeDescriptor("app::Engine"));
    }

    @Test
    void arrayType() {
        assertEquals("[I", mapper.toJvmTypeDescriptor("int[]"));
        assertEquals("[Ljava/lang/String;", mapper.toJvmTypeDescriptor("String[]"));
    }

    @Test
    void genericType() {
        assertEquals("Ljava/util/List;", mapper.toJvmTypeDescriptor("java.util.List<String>"));
    }

    @Test
    void nullAndEmpty() {
        assertEquals("V", mapper.toJvmTypeDescriptor(null));
        assertEquals("V", mapper.toJvmTypeDescriptor(""));
    }

    // --- matchesVisibility ---

    @Test
    void matchPublic() {
        assertTrue(mapper.matchesVisibility(0x0001, "public"));
        assertFalse(mapper.matchesVisibility(0x0002, "public"));
    }

    @Test
    void matchPrivate() {
        assertTrue(mapper.matchesVisibility(0x0002, "private"));
        assertFalse(mapper.matchesVisibility(0x0001, "private"));
    }

    @Test
    void matchProtected() {
        assertTrue(mapper.matchesVisibility(0x0004, "protected"));
        assertFalse(mapper.matchesVisibility(0x0001, "protected"));
    }

    @Test
    void matchInternal() {
        // package-private = no access flags
        assertTrue(mapper.matchesVisibility(0x0000, "internal"));
        assertFalse(mapper.matchesVisibility(0x0001, "internal"));
    }

    @Test
    void unknownVisibilityPasses() {
        assertTrue(mapper.matchesVisibility(0x0001, "ignore"));
    }

    // --- isFinal ---

    @Test
    void finalMethod() {
        assertTrue(mapper.isFinal(0x0010));
        assertFalse(mapper.isFinal(0x0001));
    }

    // --- Scan-enhanced constructor tests ---

    @Test
    void classFromMetadata() throws IOException {
        var metadata = new JsonObject();
        var classes = new JsonObject();
        var mainClass = new JsonObject();
        mainClass.addProperty("qualifiedName", "app::Main");
        mainClass.addProperty("simpleName", "Main");
        mainClass.addProperty("visibility", "public");
        var members = new JsonArray();
        members.add("run");
        members.add("init");
        mainClass.add("memberFunctions", members);
        mainClass.add("constructors", new JsonArray());
        classes.add("app::Main", mainClass);
        metadata.add("classes", classes);

        // Use empty temp dir (no .class files to scan)
        var m = new BytecodeSymbolMapper(metadata,
            Path.of(System.getProperty("java.io.tmpdir"), "empty_" + System.nanoTime()));
        assertEquals("app/Main", m.toJvmClassName("app::Main::run"));
        assertEquals("app/Main", m.toJvmClassName("app::Main::init"));
    }

    @Test
    void classFromClassScan(@TempDir Path tempDir) throws IOException {
        writeClassFile(tempDir, "app/Main", new String[]{"init", "run"});
        var metadata = new JsonObject();
        var m = new BytecodeSymbolMapper(metadata, tempDir);
        assertEquals("app/Main", m.toJvmClassName("app::init"));
        assertEquals("app/Main", m.toJvmClassName("app::run"));
    }

    @Test
    void metadataTakesPriority(@TempDir Path tempDir) throws IOException {
        writeClassFile(tempDir, "app/Main", new String[]{"run"});
        writeClassFile(tempDir, "app/Other", new String[]{"run"});

        var metadata = new JsonObject();
        var classes = new JsonObject();
        var otherClass = new JsonObject();
        otherClass.addProperty("qualifiedName", "app::Other");
        otherClass.addProperty("simpleName", "Other");
        otherClass.addProperty("visibility", "public");
        var members = new JsonArray();
        members.add("run");
        otherClass.add("memberFunctions", members);
        otherClass.add("constructors", new JsonArray());
        classes.add("app::Other", otherClass);
        metadata.add("classes", classes);

        var m = new BytecodeSymbolMapper(metadata, tempDir);
        assertEquals("app/Other", m.toJvmClassName("app::Other::run"));
    }

    @Test
    void fallbackForUnknown(@TempDir Path tempDir) throws IOException {
        var metadata = new JsonObject();
        var m = new BytecodeSymbolMapper(metadata, tempDir);
        assertEquals("unknown/Foo", m.toJvmClassName("unknown::Foo::bar"));
    }

    private void writeClassFile(Path dir, String internalName, String[] methods) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                 "java/lang/Object", null);

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        for (String method : methods) {
            var m = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 1);
            m.visitEnd();
        }

        cw.visitEnd();
        Path classFile = dir.resolve(internalName + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, cw.toByteArray());
    }
}
