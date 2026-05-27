package dev.topo.transform.pass;

/**
 * Free-function wildcard match for {@link MethodKey}.
 *
 * <p>{@code MethodKey} itself is a strict-identity triple; its
 * {@link MethodKey#equals equals} and {@link MethodKey#hashCode hashCode}
 * intentionally do not know about the
 * {@link MethodKey#ANY_DESCRIPTOR wildcard descriptor}. That kept a
 * footgun out of every {@code HashMap<MethodKey, V>} lookup: a wildcard
 * key's hash is computed from the literal {@code "*"} string and
 * silently misses every concrete entry it was meant to match.</p>
 *
 * <p>Wildcard matching is the sole responsibility of this helper. Use
 * it when consuming {@code .topo} declarations that did not disambiguate
 * by descriptor — pass a {@link MethodKey#ofAnyOverload(String, String)}
 * key and a concrete bytecode key; the descriptor wildcard matches in
 * either direction. The caller should treat "one wildcard matches
 * several concrete overloads" as a warning condition (the {@code .topo}
 * declaration is ambiguous against the host symbol set).</p>
 *
 * <p>See {@code MethodKey} class doc and issue
 * {@code jvm-obfuscation-and-method-key-design-gaps} for the asymmetry
 * background.</p>
 */
public final class MethodKeyMatcher {
    private MethodKeyMatcher() {}

    /**
     * Returns true when {@code a} and {@code b} match on (owner, name)
     * and on descriptor under the wildcard rule:
     * {@link MethodKey#ANY_DESCRIPTOR} on either side matches any
     * descriptor on the other; otherwise descriptors must match exactly.
     */
    public static boolean matches(MethodKey a, MethodKey b) {
        if (!a.owner().equals(b.owner())) return false;
        if (!a.name().equals(b.name())) return false;
        if (isWildcard(a) || isWildcard(b)) return true;
        return a.descriptor().equals(b.descriptor());
    }

    /** True when {@code key}'s descriptor is the
     *  {@link MethodKey#ANY_DESCRIPTOR wildcard sentinel}. */
    public static boolean isWildcard(MethodKey key) {
        return MethodKey.ANY_DESCRIPTOR.equals(key.descriptor());
    }
}
