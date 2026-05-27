package app;

import java.util.Arrays;
import java.util.concurrent.Callable;

public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 20;
    static final int ITERS_UNFRIENDLY = 20;
    static final int N = 524288; // 512K — backing arrays (a, b, c)
    // Gather-kernel iteration count must dominate friendly runtime so LoopVectorizePass
    // (targeting SIMD gather for indirect loads) has measurable signal over C2's
    // auto-vectorization of the streaming kernels.
    static final int INDICES_LEN = N * 10; // 5.24M

    static volatile float sinkFriendly;
    static volatile float sinkGathered;
    static volatile float sinkUnfriendly;

    // Shared data
    static float[] a, b, c;
    static int[] indices;

    // Friendly: simple streaming arithmetic, vectorizable
    public static void scaleAndAdd() {
        for (int i = 0; i < N; i++) {
            a[i] = b[i] * 2.5f + c[i];
        }
    }

    // Friendly: gathered access reduction (C2 cannot auto-vectorize this)
    public static void gatheredSum() {
        float sum = 0;
        for (int i = 0; i < indices.length; i++) {
            sum += a[indices[i]];
        }
        sinkGathered = sum;
    }

    // Friendly: reduction
    public static void reduceSum() {
        float sum = 0;
        for (int i = 0; i < N; i++) {
            sum += a[i];
        }
        sinkFriendly = sum;
    }

    // Orchestrator: calls all parallel-stage functions
    public static void runFriendly() {
        scaleAndAdd();
        reduceSum();
        gatheredSum();
    }

    // Unfriendly: carried dependency (not vectorizable)
    public static void runUnfriendly() {
        for (int i = 1; i < N; i++) {
            a[i] = a[i - 1] * 0.99f + a[i] * 0.01f;
        }
        sinkUnfriendly = a[N - 1];
    }

    public void run() {
        runFriendly();
        runUnfriendly();
        System.out.println("java_loop_vectorize: friendly=" + sinkFriendly
                           + " gathered=" + sinkGathered
                           + " unfriendly=" + sinkUnfriendly);
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
        a = new float[N]; b = new float[N]; c = new float[N];
        for (int i = 0; i < N; i++) { b[i] = i * 0.1f; c[i] = i * 0.2f; a[i] = i * 0.3f; }

        indices = new int[INDICES_LEN];
        int seed = 42;
        for (int i = 0; i < INDICES_LEN; i++) {
            seed = seed * 1103515245 + 12345;
            indices[i] = Math.abs(seed) % N;
        }

        var m = new Main();
        m.run();
        System.out.println("java_loop_vectorize: all assertions passed");

        // Warmup JIT
        for (int i = 0; i < WARMUP; i++) {
            runFriendly();
            runUnfriendly();
        }

        long friendlyUs = benchmarkMedianUs(ROUNDS, ITERS,
            () -> { runFriendly(); return 0; });
        System.out.println("RESULT_US_FRIENDLY=" + friendlyUs);

        // Re-init for unfriendly
        for (int i = 0; i < N; i++) a[i] = i * 0.3f;
        long unfriendlyUs = benchmarkMedianUs(ROUNDS, ITERS_UNFRIENDLY,
            () -> { runUnfriendly(); return 0; });
        System.out.println("RESULT_US_UNFRIENDLY=" + unfriendlyUs);
    }
}
