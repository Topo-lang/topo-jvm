package app;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Obfuscation benchmark — verifies internal symbols are renamed while
 * program remains functional.
 *
 * Friendly: repeated calls through internal (private) methods. After
 * ObfuscationPass, these method names are hashed but bytecode references
 * are consistently remapped, so execution is unchanged.
 *
 * Unfriendly: only public API calls (run/getResult), which are never
 * renamed by ObfuscationPass.
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 10;

    // Volatile sinks — prevent dead-code elimination
    static volatile int sinkHelper, sinkCompute, sinkResult;

    private int lastResult = 0;

    // Private: CPU-bound internal helper, writes to volatile sink.
    private void internalHelper(int x) {
        int sum = 0;
        for (int i = 0; i < 500_000; i++) {
            sum += (x + i) * 31 % 17;
        }
        sinkHelper = sum;
        lastResult = sum;
    }

    // Private: CPU-bound secret computation.
    private int secretCompute(int a, int b) {
        int sum = 0;
        for (int i = 0; i < 500_000; i++) {
            sum += (a + i) * 37 % 19 + (b + i) * 41 % 23;
        }
        sinkCompute = sum;
        return sum;
    }

    // Public: calls internal methods, verifies results.
    public void run() {
        internalHelper(42);
        int computed = secretCompute(10, 20);
        lastResult = computed;
        sinkResult = computed;
    }

    // Public: getter — never renamed.
    public int getResult() {
        return lastResult;
    }

    // Friendly: repeated calls through internal methods (obfuscated names
    // should still work via bytecode remapping).
    static int friendlyWork(Main m) {
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            m.internalHelper(i);
            sum += m.secretCompute(i, i + 1);
        }
        return sum;
    }

    // Unfriendly: only public API calls (never renamed).
    static int unfriendlyWork(Main m) {
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            m.run();
            sum += m.getResult();
        }
        return sum;
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
        assert m.getResult() != 0 : "run should produce non-zero result";
        assert sinkHelper != 0 : "internalHelper should produce non-zero result";
        assert sinkCompute != 0 : "secretCompute should produce non-zero result";

        // Override-chain sanity — pins issue
        // jvm-obfuscation-and-method-key-design-gaps. Before the
        // declaring-owner walk, Sub.polyValue and Base.polyValue
        // hashed to different obfuscated names; ((Base) sub).polyValue()
        // then dispatched to Base's body and the assertion below would
        // fail under the topo JAR while the vanilla JAR returned 200.
        Base asBase = new Sub();
        assert asBase.polyValue() == 200
            : "override broken: ((Base) new Sub()).polyValue() returned "
              + asBase.polyValue() + " — ObfuscationPass collapsed Sub.polyValue "
              + "to a different slot than Base.polyValue";

        System.out.println("java_obfuscation: all assertions passed");

        // Warmup JIT
        for (int i = 0; i < WARMUP; i++) {
            friendlyWork(m);
            unfriendlyWork(m);
        }

        // Benchmark
        long friendlyUs = benchmarkMedianUs(ROUNDS, ITERS,
            () -> friendlyWork(m));
        System.out.println("RESULT_US_FRIENDLY=" + friendlyUs);

        long unfriendlyUs = benchmarkMedianUs(ROUNDS, ITERS,
            () -> unfriendlyWork(m));
        System.out.println("RESULT_US_UNFRIENDLY=" + unfriendlyUs);
    }
}
