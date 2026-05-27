package dev.topo.transform.pass;

import java.util.Objects;

/**
 * Unique identifier for a JVM method: (owner, name, descriptor) triple.
 *
 * <p>Earlier versions of the transform layer used {@code owner + "/" + name}
 * (no descriptor) as the unique key. That collapses JVM-legal overloads —
 * two methods on the same class with the same name but different parameter
 * types hash to the same slot. Downstream effects:</p>
 *
 * <ul>
 *   <li>{@code ObfuscationPass} renames overloads to the same obfuscated
 *       name and produces invalid bytecode.</li>
 *   <li>{@code BytecodeVerifier}'s {@code methodIndex} silently validates
 *       only the last-seen overload (Checks 1, 3, 4, 5, 6).</li>
 *   <li>Any pass that resolves {@code .topo} declarations against JVM
 *       symbols inherits the same blind spot.</li>
 * </ul>
 *
 * <p>{@code MethodKey} pins all three components. Equality and hashing
 * cover (owner, name, descriptor) so that {@code helper(I)V} and
 * {@code helper(J)V} are distinct keys.</p>
 *
 * <p>For lookups that genuinely want "any overload" (e.g. a {@code .topo}
 * declaration that does not disambiguate by signature), build a key with
 * {@link #ofAnyOverload(String, String)} and feed both keys to
 * {@link MethodKeyMatcher#matches(MethodKey, MethodKey)} — the matcher
 * is the only surface that knows about the wildcard. Wildcard matching
 * is deliberately kept off the {@code MethodKey} class itself so it can
 * never be used as a hashed map key: a wildcard's {@code hashCode} is
 * computed from the literal {@code "*"} descriptor, which silently
 * misses every concrete entry it should match (the wildcard footgun
 * this split closes — see issue
 * jvm-obfuscation-and-method-key-design-gaps).</p>
 *
 * <p>Wire-contract status: this is an internal data type within
 * {@code topo-jvm/transform/} — it does not cross the JSON/process
 * boundary. The earlier "fully-qualified mangled string" form was
 * replaced by this three-field record so overload disambiguation and
 * wildcard lookups become explicit operations rather than substring
 * tricks.</p>
 */
public final class MethodKey {
    /** Sentinel descriptor for the "any-overload" wildcard form. Only
     *  {@link MethodKeyMatcher} treats this as a wildcard — for
     *  {@link #equals} and {@link #hashCode} it is a literal descriptor
     *  string, which is what keeps a wildcard key from silently missing
     *  concrete entries in a {@code HashMap} lookup. */
    public static final String ANY_DESCRIPTOR = "*";

    private final String owner;
    private final String name;
    private final String descriptor;

    private MethodKey(String owner, String name, String descriptor) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.name = Objects.requireNonNull(name, "name");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    /** Build a fully-disambiguated key. Owner is in JVM internal form (e.g. {@code app/Main}). */
    public static MethodKey of(String owner, String name, String descriptor) {
        return new MethodKey(owner, name, descriptor);
    }

    /**
     * Build a wildcard-descriptor key matching any overload of
     * {@code owner.name}. Wildcard semantics are honored only by
     * {@link MethodKeyMatcher#matches(MethodKey, MethodKey)}; for
     * {@link #equals} and {@link #hashCode} the descriptor is the
     * literal {@link #ANY_DESCRIPTOR} string, so this key is unsafe to
     * use as a {@code HashMap} lookup against concrete keys.
     */
    public static MethodKey ofAnyOverload(String owner, String name) {
        return new MethodKey(owner, name, ANY_DESCRIPTOR);
    }

    public String owner() { return owner; }
    public String name() { return name; }
    public String descriptor() { return descriptor; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MethodKey that)) return false;
        return owner.equals(that.owner)
            && name.equals(that.name)
            && descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, name, descriptor);
    }

    @Override
    public String toString() {
        return owner + "." + name + descriptor;
    }
}
