package app;

/**
 * Base of a tiny inheritance pair that the {@code obfuscation}
 * benchmark exercises so the equivalence test verifies the
 * ObfuscationPass keeps the override chain intact.
 *
 * <p>{@link #polyValue()} is non-public in the .topo declaration so
 * the obfuscator renames it. After the rename, {@link Sub} must
 * still override the same slot — otherwise
 * {@code ((Base) sub).polyValue()} no longer returns Sub's value
 * and the assertion in {@link Main#main(String[])} fails, making
 * the runtime divergence visible to the equivalence harness.</p>
 */
public class Base {
    int polyValue() { return 100; }
}
