package app;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Visibility benchmark — exercises public/protected/private/internal methods.
 * Friendly: deep internal call chain (visibility-based optimization opportunity).
 * Unfriendly: only public API calls (no cross-boundary optimization).
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 50;
    static final int ITERS_FRIENDLY = 600;
    static final int ITERS_UNFRIENDLY = 250;

    private int lastResult = 0;

    // public
    public void run() {
        int a = helperCompute(10, 20);
        logOperation(a);
        int b = helperCompute(a, 5);
        logOperation(b);
        resetState();
    }

    // public
    public int getResult() {
        return lastResult;
    }

    // protected
    protected void logOperation(int result) {
        lastResult = result;
    }

    // private
    private void resetState() {
        lastResult = 0;
    }

    // package-private (internal)
    int helperCompute(int a, int b) {
        return a + b;
    }

    // Friendly: repeated calls through internal chain (deep cross-boundary calls)
    static int friendlyWork(Main m) {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += m.helperCompute(i, i + 1);
            m.logOperation(sum);
            m.resetState();
        }
        return sum;
    }

    // Unfriendly: only public API calls (no cross-boundary optimization)
    static int unfriendlyWork(Main m) {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            m.logOperation(i);
            sum += m.getResult() * 31 % 17;
        }
        return sum;
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
        System.out.println("java_visibility: result=" + m.getResult());

        // Verify basic correctness
        assert m.getResult() == 0 : "resetState should have cleared result";

        m.logOperation(42);
        assert m.getResult() == 42 : "logOperation should set result";

        System.out.println("java_visibility: all assertions passed");

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            friendlyWork(m);
            unfriendlyWork(m);
        }

        // Benchmark
        long friendlyUs = benchmarkMedianUs(ROUNDS, ITERS_FRIENDLY, () -> friendlyWork(m));
        System.out.println("RESULT_US_FRIENDLY=" + friendlyUs);

        long unfriendlyUs = benchmarkMedianUs(ROUNDS, ITERS_UNFRIENDLY, () -> unfriendlyWork(m));
        System.out.println("RESULT_US_UNFRIENDLY=" + unfriendlyUs);
    }
}
