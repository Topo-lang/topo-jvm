package dev.topo;

/**
 * Test-only no-op stand-in for the runtime {@code dev.topo.PassEvents}
 * (which lives in {@code topo-lang-java/runtime} and is on the transformed
 * user code's classpath at real build time, exactly like
 * {@code dev.topo.Parallel} / {@code dev.topo.Arena} / etc.).
 *
 * <p>The transform unit tests that *execute* a rewritten method
 * (e.g. {@code PipelinePassTest.rewrittenOrchestratorIsFunctionallyEquivalent})
 * load the transformed bytecode with a child ClassLoader whose parent is
 * the test classpath. The per-Pass JFR injection makes those
 * methods reference {@code dev.topo.PassEvents}; without a resolvable
 * class the invoke would throw {@code NoClassDefFoundError}. This stub
 * satisfies linkage with the SAME static signatures as the runtime class
 * but commits no JFR events — the transform test only asserts semantic
 * equivalence of the rewrite, not JFR emission (that is covered by the
 * runtime {@code PassEvents} class + the topo-core JfrNdjsonConverter
 * routing e2e {@code e2e.topo-profile.jfr_pass_events_routed}).
 */
public final class PassEvents {
    private PassEvents() {}

    public static void emitAdaptiveTransition(String method, String from,
                                              String to) {
        // no-op (test stub)
    }

    public static void emitArenaLifecycle(String method, String scope,
                                          long size, String phase) {
        // no-op (test stub)
    }

    public static void emitParallelSpawn(String method, String spawnSite) {
        // no-op (test stub)
    }

    public static void emitPipelineCompose(String method, String topology) {
        // no-op (test stub)
    }
}
