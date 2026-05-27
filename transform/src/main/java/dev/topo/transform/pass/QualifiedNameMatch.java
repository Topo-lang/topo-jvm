package dev.topo.transform.pass;

import java.util.Map;
import java.util.Set;

/**
 * Qualified-name matching helpers shared by all passes.
 *
 * <p>Topo {@code .topo} namespace declarations omit the host class name,
 * so metadata qualified names look like {@code app::runFriendly}. JVM
 * class names on the other hand always carry the class (e.g.
 * {@code app/Main}). Passes must therefore try two forms when probing a
 * metadata set/map:</p>
 *
 * <ol>
 *   <li>Full class-qualified: {@code app::Main::runFriendly}</li>
 *   <li>Namespace-level: {@code app::runFriendly} (package derived from
 *       the JVM class name by stripping the class suffix)</li>
 * </ol>
 *
 * <p>Without the second form the pass silently fails to fire — the root
 * cause of {@code jvm-pass-namespace-qualification-mismatch}.</p>
 *
 * <h2>Overload semantics</h2>
 *
 * <p>{@code .topo} qualified names today carry no method descriptor;
 * declarations apply to <em>every overload</em> of the named host
 * method. Passes that need descriptor-precise behaviour route through
 * {@link MethodKey} directly; passes that only need name-level
 * affirmation (visibility, stage placement, observability) keep using
 * these helpers. This is the deliberate "name applies to all overloads"
 * semantics — see {@link MethodKey} for the descriptor-bearing form
 * passes opt into when they need overload precision.</p>
 *
 * <h2>Dual-form collision</h2>
 *
 * <p>A namespace-only metadata entry ({@code app::helper}) matches every
 * class in package {@code app/} that defines a method named
 * {@code helper}; this is intentional mass-application, not a fallback.
 * The method-key spec carries the user-facing rule. Cross-class
 * collisions are bounded by the existing
 * {@code jvm-qualified-name-match-dual-form-collision} guard tests.</p>
 */
final class QualifiedNameMatch {
    private QualifiedNameMatch() {}

    /** Full class-qualified key: {@code app/Main} + {@code runFriendly} → {@code app::Main::runFriendly}. */
    static String classQualified(String className, String methodName) {
        return className.replace("/", "::") + "::" + methodName;
    }

    /**
     * Namespace-level key: {@code app/Main} + {@code runFriendly} → {@code app::runFriendly}.
     * Returns null for classes without a package (e.g. default-package classes).
     */
    static String namespaceQualified(String className, String methodName) {
        int lastSlash = className.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String namespace = className.substring(0, lastSlash).replace("/", "::");
        return namespace + "::" + methodName;
    }

    /** True if set contains either the class-qualified or namespace-level form. */
    static boolean contains(Set<String> set, String className, String methodName) {
        if (set.contains(classQualified(className, methodName))) return true;
        String ns = namespaceQualified(className, methodName);
        return ns != null && set.contains(ns);
    }

    /** True if map has a key for either the class-qualified or namespace-level form. */
    static <V> boolean containsKey(Map<String, V> map, String className, String methodName) {
        if (map.containsKey(classQualified(className, methodName))) return true;
        String ns = namespaceQualified(className, methodName);
        return ns != null && map.containsKey(ns);
    }

    /** Look up a value by class-qualified or namespace-level key; null if neither matches. */
    static <V> V get(Map<String, V> map, String className, String methodName) {
        V v = map.get(classQualified(className, methodName));
        if (v != null) return v;
        String ns = namespaceQualified(className, methodName);
        return ns != null ? map.get(ns) : null;
    }
}
