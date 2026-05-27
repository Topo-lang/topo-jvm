package app;

/** Subclass of {@link Base} that overrides {@link Base#polyValue()}.
 *  See {@code Base} for the override-chain equivalence-test contract. */
public class Sub extends Base {
    @Override int polyValue() { return 200; }
}
