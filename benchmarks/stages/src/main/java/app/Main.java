package app;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Stages benchmark — exercises stage ordering enforcement.
 * Three stages: init -> process -> finalize.
 * Friendly: clear stage boundaries, stage isolation possible.
 * Unfriendly: cross-stage data dependencies (no reordering).
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 50;
    static final int ITERS = 300;

    private int state = 0;

    public int init() {
        state = 100;
        return state;
    }

    public int process(int data) {
        int result = 0;
        for (int i = 0; i < 100_000; i++) {
            result += data * i % 31;
        }
        state = result;
        return result;
    }

    public void finalize(int result) {
        state = result * 2;
    }

    // Friendly: clear stage boundaries (init -> process -> finalize in sequence)
    public void run() {
        int a = init();
        int b = process(a);
        finalize(b);
    }

    public int getState() { return state; }

    // Unfriendly: interleaved stage operations that prevent stage isolation
    static int unfriendlyWork(Main m) {
        int r1 = m.process(50);     // process without init
        int r2 = m.init();          // init after process
        m.finalize(r1);             // finalize with stale data
        int r3 = m.process(r2);     // process again with init result
        return r3;
    }

    private static long benchmarkMedianUs(int rounds, int iters, Callable<Integer> work) throws Exception {
        long[] samples = new long[rounds];
        for (int r = 0; r < rounds; r++) {
            long start = System.nanoTime();
            for (int it = 0; it < iters; it++) {
                work.call();
            }
            samples[r] = (System.nanoTime() - start) / 1000;
        }
        Arrays.sort(samples);
        return samples[rounds / 2];
    }

    public static void main(String[] args) throws Exception {
        var m = new Main();
        m.run();

        assert m.getState() != 0 : "finalize should set non-zero state";
        System.out.println("java_stages: state=" + m.getState());
        System.out.println("java_stages: all assertions passed");

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            m.run();
            unfriendlyWork(m);
        }

        // Benchmark
        long friendlyUs = benchmarkMedianUs(ROUNDS, ITERS, () -> { m.run(); return 0; });
        System.out.println("RESULT_US_FRIENDLY=" + friendlyUs);

        long unfriendlyUs = benchmarkMedianUs(ROUNDS, ITERS, () -> unfriendlyWork(m));
        System.out.println("RESULT_US_UNFRIENDLY=" + unfriendlyUs);
    }
}
