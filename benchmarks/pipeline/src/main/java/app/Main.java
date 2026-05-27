package app;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Pipeline benchmark — measures PipelinePass fork/join optimization.
 *
 * Friendly: fork/join DAG — parse → [enhance || detect] → compose.
 *   Source code calls enhance and detect sequentially.
 *   PipelinePass rewrites to run parallel branches via CompletableFuture.
 *   Vanilla: sequential parse + enhance + detect + compose.
 *   Topo:    parse + max(enhance, detect) + compose → ~1.5x speedup.
 *
 * Unfriendly: linear pipeline parse → transform → emit (each depends on previous).
 *   No fork → no parallelism opportunity.
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 50;

    static volatile int sink;

    // --- Pipeline stages (~2ms each post-JIT, well above CompletableFuture switch cost) ---

    public static int parse(int input) {
        int r = input;
        for (int i = 0; i < 2_000_000; i++) r = (r * 31 + i) % 1_000_003;
        return r;
    }

    public static int enhance(int data) {
        int r = data;
        for (int i = 0; i < 2_000_000; i++) r = (r * 37 + i) % 1_000_003;
        return r;
    }

    public static int detect(int data) {
        int r = data;
        for (int i = 0; i < 2_000_000; i++) r = (r * 41 + i) % 1_000_003;
        return r;
    }

    public static int compose(int enhanced, int detected) {
        return enhanced ^ detected;
    }

    // Friendly: fork/join pipeline.
    // Source calls enhance and detect sequentially on the same input (parsed).
    // .topo declares parse→enhance, parse→detect, enhance→compose, detect→compose.
    // PipelinePass can run enhance and detect in parallel.
    public static int process(int input) {
        int parsed = parse(input);
        int enhanced = enhance(parsed);
        int detected = detect(parsed);
        return compose(enhanced, detected);
    }

    // Linear stages for unfriendly workload (same cost, no fork)
    public static int transform(int data) {
        int r = data;
        for (int i = 0; i < 2_000_000; i++) r = (r * 43 + i) % 1_000_003;
        return r;
    }

    public static void emit(int result) {
        sink = result;
    }

    // Unfriendly: linear pipeline, each step depends on previous.
    // No fork/join → no parallelism even with PipelinePass.
    public static void processLinear(int input) {
        int parsed = parse(input);
        int transformed = transform(parsed);
        emit(transformed);
    }

    // Correctness check
    public void run() {
        int result = process(42);
        sink = result;
        System.out.println("java_pipeline: result=" + result);
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
        assert sink != 0 : "pipeline should produce non-zero result";
        System.out.println("java_pipeline: all assertions passed");

        // Warmup JIT
        for (int i = 0; i < WARMUP; i++) {
            process(i);
            processLinear(i);
        }

        // Benchmark
        long friendlyUs = benchmarkMedianUs(ROUNDS, ITERS,
            () -> process(ITERS));
        System.out.println("RESULT_US_FRIENDLY=" + friendlyUs);

        long unfriendlyUs = benchmarkMedianUs(ROUNDS, ITERS,
            () -> { processLinear(ITERS); return 0; });
        System.out.println("RESULT_US_UNFRIENDLY=" + unfriendlyUs);
    }
}
