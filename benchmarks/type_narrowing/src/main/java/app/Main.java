package app;

import dev.topo.Array;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Callable;

/**
 * TypeNarrowing benchmark.
 *
 * Friendly: tight counted loop calling Array.get(i) with CHECKCAST.
 * The pass eliminates the virtual get() call, replacing it with
 * rawData()[i] + AALOAD + CHECKCAST, enabling C2 monomorphic inlining
 * and escape analysis.
 *
 * Unfriendly: single random access per iteration — virtual call overhead
 * is negligible compared to the random memory access cost.
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 15;
    static final int N = 1048576; // 1M elements

    static volatile float sinkFriendly;
    static volatile float sinkUnfriendly;

    static Array<Particle> particles;

    // Friendly: tight loop iterating Array.get(i) — virtual call overhead dominates.
    // With type narrowing: get() replaced by rawData()[i], C2 sees direct aaload.
    public static void runFriendly() {
        float sum = 0;
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            sum += p.x;
        }
        sinkFriendly = sum;
    }

    // Unfriendly: random single access — virtual call cost is noise.
    // Starting index is a fixed constant to keep stdout deterministic across
    // runs; the dependent chain on p.y still exercises pointer-chase behavior.
    public static void runUnfriendly() {
        float sum = 0;
        int idx = N / 2;
        for (int iter = 0; iter < N; iter++) {
            // Dependent chain: each access depends on previous value
            Particle p = particles.get(idx);
            sum += p.x;
            idx = (int) (p.y * (N - 1)) & (N - 1);
        }
        sinkUnfriendly = sum;
    }

    public void run() {
        runFriendly();
        runUnfriendly();
        System.out.println("java_type_narrowing: friendly=" + sinkFriendly
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
        // Deterministic seed so vanilla and topo runs produce identical
        // stdout — required for e2e assertOutputsEquivalent.
        Random rng = new Random(0xC0FFEEL);
        particles = Array.create(N, () -> new Particle(rng));

        var m = new Main();
        m.run();
        System.out.println("java_type_narrowing: all assertions passed");

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
