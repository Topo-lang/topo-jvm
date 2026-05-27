package app;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Observability benchmark — measures overhead of JFR event injection.
 *
 * Friendly: orchestrator calls processData() then aggregateResults(),
 * both declared as staged in .topo. ObservabilityPass wraps each with
 * StageEvent begin/end JFR instrumentation.
 *   Vanilla: no instrumentation overhead.
 *   Topo:    JFR event begin/end around each stage call.
 *
 * Unfriendly: equivalent monolithic work (no stage functions, no
 * instrumentation points).
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 10;

    // Volatile sinks — prevent dead-code elimination
    static volatile int sinkProcess, sinkAggregate;

    // Stage<1>: CPU-bound processing (~2ms), writes to volatile sink.
    public static void processData() {
        int sum = 0;
        for (int i = 0; i < 1_000_000; i++) {
            sum += i * 31 % 17;
        }
        sinkProcess = sum;
    }

    // Stage<2>: CPU-bound aggregation (~2ms), writes to volatile sink.
    public static void aggregateResults() {
        int sum = 0;
        for (int i = 0; i < 1_000_000; i++) {
            sum += i * 37 % 19;
        }
        sinkAggregate = sum;
    }

    // Friendly orchestrator: staged calls — ObservabilityPass wraps each
    // with StageEvent begin/end.
    public static void runFriendly() {
        processData();
        aggregateResults();
    }

    // Unfriendly: monolithic equivalent work, not declared in .topo,
    // never instrumented.
    public static int runUnfriendly() {
        int sum = 0;
        for (int i = 0; i < 2_000_000; i++) {
            sum += i * 31 % 17;
        }
        return sum;
    }

    // Correctness check (called once — not benchmarked)
    public void run() {
        runFriendly();
        int u = runUnfriendly();
        System.out.println("java_observability: sinkProcess=" + sinkProcess
                           + " sinkAggregate=" + sinkAggregate
                           + " unfriendly=" + u);
    }

    private static long benchmarkMedianUs(int rounds, int iters,
                                           Callable<Integer> work) throws Exception {
        long[] samples = new long[rounds];
        for (int r = 0; r < rounds; r++) {
            long start = System.nanoTime();
            for (int it = 0; it < iters; it++) work.call();
            samples[r] = (System.nanoTime() - start) / 1000;
        }
        Arrays.sort(samples);
        return samples[rounds / 2];
    }

    public static void main(String[] args) throws Exception {
        var m = new Main();
        m.run();
        assert sinkProcess != 0 : "processData should produce non-zero result";
        assert sinkAggregate != 0 : "aggregateResults should produce non-zero result";
        System.out.println("java_observability: all assertions passed");

        // Warmup JIT
        for (int i = 0; i < WARMUP; i++) {
            runFriendly();
            runUnfriendly();
        }

        // Benchmark
        long friendlyUs = benchmarkMedianUs(ROUNDS, ITERS,
            () -> { runFriendly(); return 0; });
        System.out.println("RESULT_US_FRIENDLY=" + friendlyUs);

        long unfriendlyUs = benchmarkMedianUs(ROUNDS, ITERS,
            () -> { runUnfriendly(); return 0; });
        System.out.println("RESULT_US_UNFRIENDLY=" + unfriendlyUs);
    }
}
