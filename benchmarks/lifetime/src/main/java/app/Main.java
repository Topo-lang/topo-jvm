package app;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Lifetime arena benchmark — measures Arena lifecycle injection overhead.
 *
 * Friendly: allocWork() and sumWork() are declared with lifetime scope in .topo.
 * ArenaPass wraps them with Arena creation/close in try-finally.
 * High-frequency allocation workload benefits from deterministic cleanup.
 *
 * Unfriendly: equivalent monolithic work with no arena injection.
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 10;

    static volatile int sinkAlloc, sinkSum;

    // Stage<1>: Many medium-sized array allocations — arena scope entry.
    // ArenaPass injects: Arena __arena = new Arena(65536); try { ... } finally { __arena.close(); }
    // Array size (512 > default EliminateAllocationArraySizeLimit=64) and
    // alloc count are large enough that C2 EA cannot scalar-replace them away,
    // so arena vs. heap-alloc cost difference is measurable.
    public static void allocWork() {
        int sum = 0;
        for (int i = 0; i < 5000; i++) {
            int[] temp = new int[512];
            for (int j = 0; j < temp.length; j++) {
                temp[j] = (i + j) * 31 % 17;
            }
            sum += temp[i & 511];
        }
        sinkAlloc = sum;
    }

    // Stage<2>: More allocations within same scope.
    public static void sumWork() {
        int sum = 0;
        for (int i = 0; i < 3000; i++) {
            int[] temp = new int[512];
            for (int j = 0; j < temp.length; j++) {
                temp[j] = (i + j) * 37 % 19;
            }
            sum += temp[i & 511];
        }
        sinkSum = sum;
    }

    // Friendly orchestrator: staged calls with arena lifecycle injection.
    public static void runFriendly() {
        allocWork();
        sumWork();
    }

    // Unfriendly: monolithic equivalent work, not declared in .topo.
    public static int runUnfriendly() {
        int sum = 0;
        for (int i = 0; i < 8000; i++) {
            int[] temp = new int[512];
            for (int j = 0; j < temp.length; j++) {
                temp[j] = (i + j) * 31 % 17;
            }
            sum += temp[i & 511];
        }
        return sum;
    }

    public void run() {
        runFriendly();
        int u = runUnfriendly();
        System.out.println("java_lifetime: sinkAlloc=" + sinkAlloc
                           + " sinkSum=" + sinkSum
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
        assert sinkAlloc != 0 : "allocWork should produce non-zero result";
        assert sinkSum != 0 : "sumWork should produce non-zero result";
        System.out.println("java_lifetime: all assertions passed");

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
