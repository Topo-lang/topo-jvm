package dev.topo.transform.pass;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

/**
 * Base interface for all bytecode transformation passes.
 */
public interface BasePass {
    /** Create an ASM ClassVisitor that implements this pass's transformation. */
    ClassVisitor createVisitor(ClassWriter writer);
}
