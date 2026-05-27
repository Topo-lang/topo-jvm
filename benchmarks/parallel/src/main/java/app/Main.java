package app;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Parallel benchmark — measures ParallelPass bytecode transformation.
 *
 * Friendly: orchestrator calls 4 independent void static tasks sequentially
 * in the source code. All tasks are declared stage<1> in .topo, so ParallelPass
 * rewrites them into Parallel.spawn() + awaitAllVoid().
 *   Vanilla: sequential ~4T.
 *   Topo:    parallel   ~T on 4+ cores.
 *
 * Unfriendly: same total work in a monolithic loop (not an orchestrator in .topo).
 *   Both vanilla and topo: sequential ~4T, no transformation.
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 10;

    // Volatile sinks — prevent dead-code elimination
    static volatile int sinkA, sinkB, sinkC, sinkD;

    // Four independent CPU-bound tasks. Scaled so each task body (>>10ms post-JIT)
    // dominates ForkJoinPool dispatch cost — signal survives noise floor.
    // void, static, no params → matches ParallelPass INVOKESTATIC ()V filter.
    public static void taskA() {
        int sum = 0;
        for (int i = 0; i < 20_000_000; i++) sum += i * 31 % 17;
        sinkA = sum;
    }

    public static void taskB() {
        int sum = 0;
        for (int i = 0; i < 20_000_000; i++) sum += i * 37 % 19;
        sinkB = sum;
    }

    public static void taskC() {
        int sum = 0;
        for (int i = 0; i < 20_000_000; i++) sum += i * 41 % 23;
        sinkC = sum;
    }

    public static void taskD() {
        int sum = 0;
        for (int i = 0; i < 20_000_000; i++) sum += i * 43 % 29;
        sinkD = sum;
    }

    // Friendly orchestrator: 4 independent tasks, all stage<1> in .topo.
    // ParallelPass wraps each call in Parallel.spawn(), adds awaitAllVoid.
    public static void runFriendly() {
        taskA();
        taskB();
        taskC();
        taskD();
    }

    // Unfriendly: equivalent total work in a monolithic loop.
    // Not declared as orchestrator in .topo → never transformed.
    public static int runUnfriendly() {
        int sum = 0;
        for (int i = 0; i < 80_000_000; i++) {
            sum += i * 31 % 17;
        }
        return sum;
    }

    // Correctness check (called once — not benchmarked)
    public void run() {
        runFriendly();
        int u = runUnfriendly();
        System.out.println("java_parallel: sinkA=" + sinkA + " sinkB=" + sinkB
                           + " sinkC=" + sinkC + " sinkD=" + sinkD
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
        assert sinkA != 0 : "taskA should produce non-zero result";
        System.out.println("java_parallel: all assertions passed");

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
