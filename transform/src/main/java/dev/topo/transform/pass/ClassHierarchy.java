package dev.topo.transform.pass;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Read-only class hierarchy snapshot built once per
 * {@link dev.topo.transform.PassPipeline} run.
 *
 * <p>Records, for every class in the input batch:</p>
 * <ul>
 *   <li>its superclass internal name ({@link #superOf}),</li>
 *   <li>the {@link MethodKey} set of <em>overridable</em> methods it
 *       locally declares — i.e. instance (non-static) methods that are
 *       not private and not {@code <init>}/{@code <clinit>}
 *       ({@link #declaresOverridable}).</li>
 * </ul>
 *
 * <p>The single use right now is
 * {@link ObfuscationPass#resolveDeclaringOwner(MethodKey)}, which walks
 * upward to find the topmost ancestor that introduces a given
 * overridable method so the obfuscated name is shared across the
 * override chain. Without this, a subclass override hashes under its
 * own owner and the JVM treats the override as an unrelated method —
 * polymorphism silently breaks (see issue
 * {@code jvm-obfuscation-and-method-key-design-gaps}).</p>
 *
 * <p>Classes outside the input batch (JDK classes, third-party libs,
 * runtime jar) are not present in the maps — the hierarchy walk in
 * {@code resolveDeclaringOwner} naturally stops the first time it asks
 * about an unknown parent, which is the right behavior: we cannot rename
 * JDK methods anyway.</p>
 */
public final class ClassHierarchy {

    private final Map<String, String> superOfMap;
    private final Set<MethodKey> overridableSet;

    private ClassHierarchy(Map<String, String> superOfMap,
                           Set<MethodKey> overridableSet) {
        this.superOfMap = superOfMap;
        this.overridableSet = overridableSet;
    }

    /** Internal superclass name, or {@code null} if the class is unknown
     *  to the batch (e.g. {@code java/lang/Object} or a third-party
     *  library). */
    public String superOf(String ownerInternal) {
        return superOfMap.get(ownerInternal);
    }

    /** True when {@code key.owner} locally declares an overridable
     *  method matching {@code key.name + key.descriptor}. */
    public boolean declaresOverridable(MethodKey key) {
        return overridableSet.contains(key);
    }

    /** Builds a hierarchy snapshot from the given class file bytes.
     *  Pass the same byte arrays the rewrite loop will consume. */
    public static ClassHierarchy fromClassBytes(Iterable<byte[]> classFiles) {
        Map<String, String> superOf = new HashMap<>();
        Set<MethodKey> overridable = new HashSet<>();

        for (byte[] bytes : classFiles) {
            ClassReader reader = new ClassReader(bytes);
            String thisOwner = reader.getClassName();
            String superName = reader.getSuperName(); // null only for java/lang/Object
            superOf.put(thisOwner, superName);

            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (isOverridable(access, name)) {
                        overridable.add(MethodKey.of(thisOwner, name, descriptor));
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        return new ClassHierarchy(superOf, overridable);
    }

    /** A method participates in the override chain only when it is an
     *  instance method, not private, and not a constructor / class
     *  initializer. Static and private methods are statically dispatched
     *  by name+owner so they keep their owner-local rename hash. */
    static boolean isOverridable(int access, String name) {
        if ((access & Opcodes.ACC_STATIC) != 0) return false;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return false;
        if ("<init>".equals(name) || "<clinit>".equals(name)) return false;
        return true;
    }
}
