package dev.topo.transform.pass;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * polymorphism silently breaks.</p>
 *
 * <p>Classes outside the input batch (JDK classes, third-party libs,
 * runtime jar) are not present in the maps — the hierarchy walk in
 * {@code resolveDeclaringOwner} naturally stops the first time it asks
 * about an unknown parent, which is the right behavior: we cannot rename
 * JDK methods anyway.</p>
 */
public final class ClassHierarchy {

    private final Map<String, String> superOfMap;
    private final Map<String, List<String>> interfacesOfMap;
    private final Set<MethodKey> overridableSet;

    private ClassHierarchy(Map<String, String> superOfMap,
                           Map<String, List<String>> interfacesOfMap,
                           Set<MethodKey> overridableSet) {
        this.superOfMap = superOfMap;
        this.interfacesOfMap = interfacesOfMap;
        this.overridableSet = overridableSet;
    }

    /** Internal superclass name, or {@code null} if the class is unknown
     *  to the batch (e.g. {@code java/lang/Object} or a third-party
     *  library). */
    public String superOf(String ownerInternal) {
        return superOfMap.get(ownerInternal);
    }

    /** Directly-declared interface internal names for {@code ownerInternal},
     *  or an empty list if the class is unknown to the batch or declares
     *  none. The list is the {@code interfaces[]} array as recorded by the
     *  class file, so {@code invokeinterface} override resolution can follow
     *  interface edges the superclass walk alone misses. */
    public List<String> interfacesOf(String ownerInternal) {
        return interfacesOfMap.getOrDefault(ownerInternal, Collections.emptyList());
    }

    /** True when {@code key.owner} locally declares an overridable
     *  method matching {@code key.name + key.descriptor}. */
    public boolean declaresOverridable(MethodKey key) {
        return overridableSet.contains(key);
    }

    /**
     * Resolves the canonical declaring owner for an overridable method so
     * every class/interface linked by override (superclass chain AND
     * interface implementation) shares one obfuscated name.
     *
     * <p>Returns {@code leaf} unchanged when the leaf does not locally
     * declare an overridable method (static / private / constructor — the
     * JVM dispatches those statically, so they keep their owner-local
     * name).</p>
     *
     * <p>Otherwise computes the <em>undirected connected component</em> of
     * owners that all declare {@code (leaf.name, leaf.descriptor)} as an
     * overridable method and are linked through super or interface edges in
     * either direction. The component is identical regardless of which
     * member it is computed from, so the chosen representative — the
     * lexicographically smallest owner — is stable across the base class,
     * every subclass, the declaring interface, and every implementer. This
     * is what keeps an {@code invokeinterface} override and its
     * implementation under the same name; following only the superclass
     * chain (the previous behavior) missed interface edges entirely and
     * renamed the two apart, yielding {@code AbstractMethodError}.</p>
     */
    public MethodKey canonicalDeclaringOwner(MethodKey leaf) {
        if (!overridableSet.contains(leaf)) return leaf;

        String name = leaf.name();
        String desc = leaf.descriptor();

        // Undirected adjacency: an edge connects two declarers when one is
        // the other's superclass or a directly-declared interface. Walk both
        // directions by scanning every known owner's super/interface edges.
        Set<String> component = new HashSet<>();
        java.util.Deque<String> work = new java.util.ArrayDeque<>();
        component.add(leaf.owner());
        work.add(leaf.owner());

        while (!work.isEmpty()) {
            String owner = work.poll();

            // Upward edges from `owner`: superclass + declared interfaces.
            List<String> parents = new java.util.ArrayList<>();
            String sup = superOfMap.get(owner);
            if (sup != null) parents.add(sup);
            parents.addAll(interfacesOf(owner));
            for (String parent : parents) {
                if (declares(parent, name, desc) && component.add(parent)) {
                    work.add(parent);
                }
            }

            // Downward edges: any known owner whose super/interface list
            // includes `owner`. Requires a scan since the maps are forward-only.
            for (String candidate : superOfMap.keySet()) {
                if (component.contains(candidate)) continue;
                boolean linked = owner.equals(superOfMap.get(candidate))
                    || interfacesOf(candidate).contains(owner);
                if (linked && declares(candidate, name, desc)) {
                    component.add(candidate);
                    work.add(candidate);
                }
            }
        }

        String canonical = leaf.owner();
        for (String owner : component) {
            if (owner.compareTo(canonical) < 0) canonical = owner;
        }
        return MethodKey.of(canonical, name, desc);
    }

    private boolean declares(String owner, String name, String descriptor) {
        return overridableSet.contains(MethodKey.of(owner, name, descriptor));
    }

    /** Builds a hierarchy snapshot from the given class file bytes.
     *  Pass the same byte arrays the rewrite loop will consume. */
    public static ClassHierarchy fromClassBytes(Iterable<byte[]> classFiles) {
        Map<String, String> superOf = new HashMap<>();
        Map<String, List<String>> interfacesOf = new HashMap<>();
        Set<MethodKey> overridable = new HashSet<>();

        for (byte[] bytes : classFiles) {
            ClassReader reader = new ClassReader(bytes);
            String thisOwner = reader.getClassName();
            String superName = reader.getSuperName(); // null only for java/lang/Object
            superOf.put(thisOwner, superName);
            String[] ifaces = reader.getInterfaces(); // never null; may be empty
            if (ifaces != null && ifaces.length > 0) {
                interfacesOf.put(thisOwner, List.of(ifaces));
            }

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

        return new ClassHierarchy(superOf, interfacesOf, overridable);
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
