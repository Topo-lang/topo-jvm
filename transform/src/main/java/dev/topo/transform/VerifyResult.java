package dev.topo.transform;

/**
 * Result of bytecode verification against Topo declarations.
 *
 * <p>The historical "10 verification checks" wording is retired. Today's
 * BytecodeVerifier runs 8 declaration-derived checks plus 1 heuristic;
 * Check 9 (constraint satisfaction) is delegated to javac and has no
 * Topo-side counter. The renamed Check 10 counter
 * {@code sharedMutableFieldWrites} reflects the narrow pairwise-write
 * heuristic it actually implements (see
 * {@code BytecodeVerifier#checkSharedMutableFieldWrites}).</p>
 *
 * <p>Counter ↔ check mapping:</p>
 * <table>
 *   <caption>BytecodeVerifier counter / check mapping</caption>
 *   <tr><th>Counter</th><th>Check</th></tr>
 *   <tr><td>{@code publicMissing}</td><td>Check 1 — public symbols exist</td></tr>
 *   <tr><td>{@code blockMismatches}</td><td>Check 2 — logic-block callees exist</td></tr>
 *   <tr><td>{@code signatureMismatches}</td><td>Check 3 — declared param count matches</td></tr>
 *   <tr><td>{@code constMismatches}</td><td>Check 4 — declared const ↔ final modifier</td></tr>
 *   <tr><td>{@code classMemberMissing}</td><td>Check 5 — declared class member exists</td></tr>
 *   <tr><td>{@code stageOrderViolations}</td><td>Check 6 — stage order in call graph</td></tr>
 *   <tr><td>{@code pipelineEdgeMismatches}</td><td>Check 7 — pipeline-edge endpoints exist</td></tr>
 *   <tr><td>{@code templateInstantiationMissing}</td><td>Check 8 — erased generic type exists</td></tr>
 *   <tr><td><em>(no counter)</em></td><td>Check 9 — delegated to javac (see {@link #javacDeferredNote})</td></tr>
 *   <tr><td>{@code sharedMutableFieldWrites}</td><td>Check 10 — shared-mutable-field write detection (heuristic)</td></tr>
 *   <tr><td>{@code classLoadFailures}</td><td>Loader — .class read or ASM parse failure (surfaces as verification error so downstream "missing method" reports remain distinguishable from "couldn't load the class")</td></tr>
 * </table>
 */
public class VerifyResult {
    public int publicMissing = 0;
    public int blockMismatches = 0;
    public int signatureMismatches = 0;
    public int constMismatches = 0;
    public int classMemberMissing = 0;
    public int stageOrderViolations = 0;
    public int pipelineEdgeMismatches = 0;
    public int templateInstantiationMissing = 0;
    /**
     * Counter for the renamed Check 10 — shared-mutable-field write
     * detection (heuristic). Replaces the historical
     * {@code stageParallelViolations} field; renamed alongside the check
     * itself to match the narrow pairwise-write semantics it actually
     * implements.
     */
    public int sharedMutableFieldWrites = 0;
    /**
     * Number of .class files that failed to load — either an
     * {@link java.io.IOException} reading bytes or an
     * {@link IllegalArgumentException} from ASM's {@code ClassReader} on
     * malformed bytecode. Surfaced so a transient I/O error or a corrupt
     * class file no longer silently downgrades to "method missing" — the
     * load failure itself becomes a verification error and {@link #passed}
     * returns false.
     */
    public int classLoadFailures = 0;
    public java.util.List<String> errors = new java.util.ArrayList<>();

    /**
     * Static note: Check 9 (constraint satisfaction) is delegated to
     * javac. No Topo-side counter increments for it. Documented here so
     * downstream consumers reading the result schema know the slot is
     * intentionally empty, not an unimplemented stub.
     */
    public static final String javacDeferredNote =
        "Check 9 (constraint satisfaction) is delegated to javac; no Topo-side counter";

    public boolean passed() {
        return publicMissing == 0 && blockMismatches == 0 &&
               signatureMismatches == 0 && constMismatches == 0 &&
               classMemberMissing == 0 && stageOrderViolations == 0 &&
               pipelineEdgeMismatches == 0 && templateInstantiationMissing == 0 &&
               sharedMutableFieldWrites == 0 &&
               classLoadFailures == 0;
    }
}
