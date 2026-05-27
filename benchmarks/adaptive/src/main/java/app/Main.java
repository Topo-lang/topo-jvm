package app;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.concurrent.Callable;

import dev.topo.Adaptive;

/**
 * Adaptive benchmark — exercises the pass-driven auto-invalidation pipeline.
 *
 * Wiring:
 *   - computeA() / computeB() are stage-declared in main.topo. Under forced
 *     mode AdaptivePass injects at each entry:
 *       Adaptive.checkDispatch("app::Main::<method>")     // no-op hook
 *       ++__topo_adaptive_hits_<method>                   // counter
 *       (hits & 7 == 0) → AdaptiveMonitor.tick(name, 8, 100)
 *   - AdaptiveMonitor accumulates across calls; after 100 hits it calls
 *     Adaptive.invalidate(name) once, transitioning to ACTIVE.
 *   - The benchmark registers fastImpl / slowImpl under the exact name
 *     "app::Main::computeA" so when AdaptiveMonitor invalidates it, the
 *     MethodHandle returned from register() flips to fallback (slowImpl)
 *     for the rest of the run — observable as increased runFriendly time.
 *
 * No manual invalidate(): invalidation is fully driven by the pass-injected
 * tick + the runtime AdaptiveMonitor. Benchmark source no longer fakes the
 * adaptive lifecycle.
 *
 * Friendly: computeA + computeB + adaptiveHandle.invokeExact (fast until
 *   threshold, slow after — demonstrates pass-driven dispatch switch).
 * Unfriendly: monolithic loop, not declared in .topo — no instrumentation.
 */
public class Main {
    static final int ROUNDS = 7;
    static final int WARMUP = 20;
    static final int ITERS = 10;

    static volatile int sinkA, sinkB, sinkAdaptive;

    // Registered under the qualified name AdaptivePass uses for computeA.
    // When AdaptiveMonitor auto-invalidates this name after warmupHits is
    // reached, this handle permanently flips to slowImpl.
    static final MethodHandle adaptiveHandle;

    public static int fastImpl() { return 1; }
    public static int slowImpl() {
        int s = 0;
        for (int i = 0; i < 256; i++) s += i * 31 % 7;
        return s;
    }

    static {
        try {
            var lookup = MethodHandles.lookup();
            var fast = lookup.findStatic(Main.class, "fastImpl", MethodType.methodType(int.class));
            var slow = lookup.findStatic(Main.class, "slowImpl", MethodType.methodType(int.class));
            adaptiveHandle = Adaptive.register("app::Main::computeA", fast, slow);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void computeA() {
        int sum = 0;
        for (int i = 0; i < 3_000_000; i++) {
            sum += i * 31 % 17;
        }
        sinkA = sum;
    }

    public static void computeB() {
        int sum = 0;
        for (int i = 0; i < 3_000_000; i++) {
            sum += i * 37 % 19;
        }
        sinkB = sum;
    }

    public static void runFriendly() {
        computeA();
        computeB();
        try {
            int r = (int) adaptiveHandle.invokeExact();
            sinkAdaptive = r;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int runUnfriendly() {
        int sum = 0;
        for (int i = 0; i < 6_000_000; i++) {
            sum += i * 31 % 17;
        }
        return sum;
    }

    public void run() {
        runFriendly();
        int u = runUnfriendly();
        System.out.println("java_adaptive: sinkA=" + sinkA
                           + " sinkB=" + sinkB
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
        assert sinkA != 0 : "computeA should produce non-zero result";
        assert sinkB != 0 : "computeB should produce non-zero result";
        System.out.println("java_adaptive: all assertions passed");

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
