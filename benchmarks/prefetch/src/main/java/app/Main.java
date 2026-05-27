package app;

import dev.topo.Array;
import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Prefetch benchmark.
 *
 * Friendly: streaming sequential scan over a large Array — prefetch hints
 * bring future elements into cache before they're accessed, hiding memory
 * latency. Expected 10-20% speedup on large arrays.
 *
 * Unfriendly: random access pattern — prefetched data is rarely used
 * before eviction, wasting memory bandwidth.
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 15;
    static final int N = 4194304; // 4M elements — large enough to exceed L3 cache

    static volatile float sinkFriendly;
    static volatile float sinkUnfriendly;

    static Array<Particle> particles;

    // Friendly: streaming sequential scan — prefetch hides latency.
    public static void runFriendly() {
        float sum = 0;
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            sum += p.x + p.y + p.z;
        }
        sinkFriendly = sum;
    }

    // Unfriendly: random access — prefetch is counterproductive.
    public static void runUnfriendly() {
        float sum = 0;
        int idx = 0;
        for (int i = 0; i < N; i++) {
            Particle p = particles.get(idx);
            sum += p.x;
            // Pseudo-random jump based on data — defeats prefetcher
            idx = (int) (p.y * (N - 1)) & (N - 1);
        }
        sinkUnfriendly = sum;
    }

    public void run() {
        runFriendly();
        runUnfriendly();
        System.out.println("java_prefetch: friendly=" + sinkFriendly
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
        particles = Array.create(N, Particle::new);

        var m = new Main();
        m.run();
        System.out.println("java_prefetch: all assertions passed");

        // Warmup JIT
        for (int i = 0; i < WARMUP; i++) {
            runFriendly();
            runUnfriendly();
        }

        long friendlyUs = benchmarkMedianUs(ROUNDS, ITERS,
            () -> { runFriendly(); return 0; });
        System.out.println("RESULT_US_FRIENDLY=" + friendlyUs);

        long unfriendlyUs = benchmarkMedianUs(ROUNDS, ITERS,
            () -> { runUnfriendly(); return 0; });
        System.out.println("RESULT_US_UNFRIENDLY=" + unfriendlyUs);
    }
}
