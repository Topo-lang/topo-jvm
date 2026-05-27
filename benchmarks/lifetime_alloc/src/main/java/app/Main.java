package app;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Lifetime arena allocation replacement benchmark.
 *
 * Friendly: hot-loop primitive array allocations within an arena scope.
 * NEWARRAY instructions are replaced with Arena.allocateXxxArray() calls,
 * avoiding GC pressure from thousands of short-lived arrays.
 *
 * Unfriendly: arrays escape the scope (stored in a list that outlives
 * the method), so arena replacement would be incorrect — these arrays
 * must use normal heap allocation.
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 10;
    static final int LOOP_COUNT = 2000;
    static final int ARRAY_SIZE = 128;

    static volatile float sinkFloat;
    static volatile int sinkInt;

    // Stage<1>: Hot float[] allocation loop — arena scope entry.
    // Each iteration creates a new float[128], fills it, and discards it.
    // With arena replacement: float[] becomes MemorySegment from arena,
    // no GC pressure from transient arrays.
    public static void hotFloatAlloc() {
        float sum = 0.0f;
        for (int i = 0; i < LOOP_COUNT; i++) {
            float[] buf = new float[ARRAY_SIZE];
            for (int j = 0; j < buf.length; j++) {
                buf[j] = (i * 31 + j) * 0.01f;
            }
            sum += buf[ARRAY_SIZE / 2];
        }
        sinkFloat = sum;
    }

    // Stage<2>: Hot int[] allocation loop — same scope.
    public static void hotIntAlloc() {
        int sum = 0;
        for (int i = 0; i < LOOP_COUNT; i++) {
            int[] buf = new int[ARRAY_SIZE];
            for (int j = 0; j < buf.length; j++) {
                buf[j] = (i + j) * 37 % 19;
            }
            sum += buf[0];
        }
        sinkInt = sum;
    }

    // Friendly orchestrator: staged calls within arena scope.
    public static void runFriendly() {
        hotFloatAlloc();
        hotIntAlloc();
    }

    // Unfriendly: arrays escape to a list that outlives the method.
    // Arena replacement would cause use-after-close, so these must
    // remain normal heap allocations.
    public static int runUnfriendly() {
        List<float[]> escaped = new ArrayList<>();
        for (int i = 0; i < LOOP_COUNT; i++) {
            float[] buf = new float[ARRAY_SIZE];
            for (int j = 0; j < buf.length; j++) {
                buf[j] = (i * 31 + j) * 0.01f;
            }
            escaped.add(buf);
        }
        // Use the escaped arrays after the allocation loop
        float total = 0.0f;
        for (float[] arr : escaped) {
            total += arr[ARRAY_SIZE / 2];
        }
        return (int) total;
    }

    public void run() {
        runFriendly();
        int u = runUnfriendly();
        System.out.println("java_lifetime_alloc: sinkFloat=" + sinkFloat
                           + " sinkInt=" + sinkInt
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
        assert sinkFloat != 0.0f : "hotFloatAlloc should produce non-zero result";
        assert sinkInt != 0 : "hotIntAlloc should produce non-zero result";
        System.out.println("java_lifetime_alloc: all assertions passed");

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
