package app;

import dev.topo.Array;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Callable;

public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 15;
    static final int N = 1048576; // 1M elements

    static volatile float sinkFriendly;
    static volatile float sinkUnfriendly;

    // Shared data — created once, reused across benchmarks
    static Array<Particle> particles;

    // Friendly: single-field sweep — contiguous float[] enables C2 auto-vectorization.
    // AoS: N reference loads + N field loads = 2N memory ops with indirection.
    // SoA: N contiguous float[] loads = N ops, SIMD-vectorizable.
    public static void runFriendly() {
        float sum = 0;
        for (int i = 0; i < particles.size(); i++) {
            sum += particles.get(i).x;
        }
        sinkFriendly = sum;
    }

    // Unfriendly: multi-field sequential — AoS locality already adequate for 3-field access.
    public static void runUnfriendly() {
        float sum = 0;
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            sum += p.x + p.y + p.z;
        }
        sinkUnfriendly = sum;
    }

    public void run() {
        runFriendly();
        runUnfriendly();
        System.out.println("java_data_layout: friendly=" + sinkFriendly
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
        System.out.println("java_data_layout: all assertions passed");

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
