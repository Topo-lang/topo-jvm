#ifndef TOPO_TEST_E2E_HARNESS_H
#define TOPO_TEST_E2E_HARNESS_H

#include <cstdint>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>

#include <gtest/gtest.h>

namespace fs = std::filesystem;

namespace topo::test::e2e {

struct RunResult {
    int exitCode = -1;
    std::string output;
};

// ---------------------------------------------------------------------------
// TopoTomlSwap — RAII guard for the Topo-base / Topo-forced swap pattern
// ---------------------------------------------------------------------------
//
// The benchmark harness used to swap ``Topo.toml`` ↔ ``Topo-base.toml`` /
// ``Topo-forced.toml`` with an ad-hoc save/restore pair around the
// build call. If the build threw or a gtest ASSERT triggered an early
// return, the restore never ran and the project's ``Topo.toml`` stayed
// permanently in the swapped state — symptomatic of the lingering
// ``Topo.toml.saved`` files reported on benchmark dirs.
//
// This RAII wrapper makes the restore unconditional: the destructor
// fires on normal exit, on exception unwind, on ASSERT-driven return,
// and on any other early-exit path the test body can take. Two failure
// modes are still possible — process kill (SIGKILL) and OS crash — but
// both leave the saved file present, which the existing CI tripwire
// already flags.
//
// Usage:
//   {
//       TopoTomlSwap swap(projDir, "Topo-base.toml");
//       if (!swap.engaged()) return RunResult{-1, swap.error()};
//       result = topoBuild(projectName);
//   } // destructor restores Topo.toml here, even on early return / throw
struct TopoTomlSwap {
    // Move-only; copying would double-restore.
    TopoTomlSwap(const TopoTomlSwap&) = delete;
    TopoTomlSwap& operator=(const TopoTomlSwap&) = delete;
    TopoTomlSwap(TopoTomlSwap&&) = delete;
    TopoTomlSwap& operator=(TopoTomlSwap&&) = delete;

    /// Swap ``<projDir>/Topo.toml`` against ``<projDir>/<altTomlName>``.
    /// On failure (alt missing, copy errored) ``engaged()`` returns false
    /// and ``error()`` carries the diagnostic; in that state the dtor is
    /// a no-op.
    TopoTomlSwap(const std::filesystem::path& projDir,
                 const std::string& altTomlName);
    ~TopoTomlSwap();

    bool engaged() const { return engaged_; }
    const std::string& error() const { return error_; }

private:
    std::filesystem::path projDir_;
    std::filesystem::path topoToml_;
    std::filesystem::path saved_;
    bool engaged_ = false;
    std::string error_;
};

// ---------------------------------------------------------------------------
// Benchmark sampling primitives
// ---------------------------------------------------------------------------

/// One measurement of a benchmark workload.
struct BenchSample {
    double us = 0.0; // single-run measured microseconds
    int runIdx = 0;  // 0-based sample index
};

/// Aggregate statistics over a sequence of `BenchSample`s.
///
/// Produced by `measureWithVarianceAdapt`. `resampleCapHit` is true iff the
/// `maxRuns` cap was reached before the coefficient of variation fell at or
/// below the target threshold — callers that care about signal reliability
/// MUST inspect this flag.
struct BenchStats {
    double median = 0.0;
    double mean = 0.0;
    double stdev = 0.0;            // sample stdev, sqrt(Σ(xᵢ-μ)²/(n-1))
    double cv = 0.0;               // stdev / mean
    int runs = 0;
    bool resampleCapHit = false;   // true if maxRuns reached before CV ≤ target
    std::vector<BenchSample> samples;
};

/// Measure `fn` repeatedly with variance adaptation.
///
/// - Run at least `minRuns`
/// - If CV > `cvTarget`, continue until CV ≤ cvTarget or `maxRuns`
/// - Returns `BenchStats` with `resampleCapHit=true` if cap reached
///
/// `fn` returns microseconds for one run; a return value ≤ 0 indicates the
/// sample failed (missing output / run error) and is recorded but excluded
/// from statistics. If all samples fail the resulting stats are zero-valued
/// and `resampleCapHit` is true.
BenchStats measureWithVarianceAdapt(std::function<double()> fn,
                                    int minRuns = 3,
                                    int maxRuns = 10,
                                    double cvTarget = 0.05);

/// Interleaved variance-adaptive measurement of N labelled probes.
/// See the LLVM-side counterpart in `topo-llvm/test/e2e/E2eHarness.h`
/// for the full rationale; in short, round-robins sampling across
/// every probe so transient OS/CPU noise hits all modes in the same
/// wall-clock windows. Eliminates the "vanilla measured during a quiet
/// window, auto measured during a noisy window" ratio swing that broke
/// run-over-run reproducibility on the same seed.
std::vector<BenchStats> measureWithVarianceAdaptInterleaved(
    std::vector<std::function<double()>> fns,
    int minRuns = 3,
    int maxRuns = 10,
    double cvTarget = 0.05);

/// Benchmark seed accessor (resolved lazily from `TOPO_BENCH_SEED` env, or
/// `std::random_device` if unset). Printed once via `[ SEED   ]` on first
/// call and propagated to `::testing::GTEST_FLAG(random_seed)` so that GTest
/// test-shuffling (if enabled) also honours the same seed.
///
/// IMPORTANT: this seed only reproduces gtest test execution order. It
/// does NOT make absolute microsecond timings or vanilla-vs-auto ratios
/// reproducible — no benchmark workload consumes the seed, and timing
/// variance is dominated by OS scheduler / CPU thermals / page-cache
/// state, all outside the reach of a userspace PRNG seed. See the
/// LLVM-side header for the longer caveat list.
std::uint32_t benchSeed();

// ---------------------------------------------------------------------------
// Category assertion helpers
// ---------------------------------------------------------------------------
//
// Per the feature-taxonomy category contract. Each helper
// applies the relevant ratio thresholds, emits `[ WARN   ]` / `[ ERROR  ]`
// coloured output lines, and promotes ERRORs to `ADD_FAILURE()` so CTest
// treats the case as failed.
//
// Absolute rules (not category-exempt):
//   1. auto/base > 1.10                     -> unconditional ERROR
//   2. forced without IR/bytecode diff      -> enforced in equivalence layer
//   3. absolute time < 10ms (10000 us)      -> skip all threshold checks
//   4. resampleCapHit (10 runs, CV > 0.05)  -> downgrade ERROR -> WARN

/// Workload flavour — the thresholds for OPT differ between friendly
/// (expected speedup) and unfriendly (expected neutral / mild slowdown).
enum class Workload { Friendly, Unfriendly };

void assertOptCategoryContract(const BenchStats& vanilla,
                               const BenchStats& base,
                               const BenchStats& autoStats,
                               const BenchStats& forced,
                               Workload workload,
                               const char* passName);

void assertEnhanceCategoryContract(const BenchStats& vanilla,
                                   const BenchStats& base,
                                   const BenchStats& autoStats,
                                   const BenchStats& forced,
                                   Workload workload,
                                   const char* passName);

void assertCoveredCategoryContract(const BenchStats& vanilla,
                                   const BenchStats& base,
                                   const BenchStats& autoStats,
                                   const BenchStats& forced,
                                   Workload workload,
                                   const char* passName);

void assertInstrumentCategoryContract(const BenchStats& vanilla,
                                      const BenchStats& base,
                                      const BenchStats& autoStats,
                                      const BenchStats& forced,
                                      Workload workload,
                                      const char* passName);

void assertRuntimeCategoryContract(const BenchStats& vanilla,
                                   const BenchStats& base,
                                   const BenchStats& autoStats,
                                   const BenchStats& forced,
                                   Workload workload,
                                   const char* passName);

// INFRA: no benchmark helper by design — INFRA passes do not enter the
// benchmark suite (per the feature-taxonomy contract). If a benchmark
// attempts to use the INFRA category via the macro below, link-time will
// fail because `assertInfraCategoryContract` is intentionally undefined.

// ---------------------------------------------------------------------------
// CATEGORY_BENCH_TEST_F — wraps TEST_F with a category property
// ---------------------------------------------------------------------------
//
// Mirror of the LLVM harness macro; see `topo-llvm/test/e2e/E2eHarness.h`
// for the explanatory prose. Kept in sync manually because the two harness
// headers are sibling translation units that already intentionally mirror
// each other.
#define CATEGORY_BENCH_TEST_F(Category, Suite, Name)                           \
    class Suite##_##Category##_##Name : public Suite {                         \
      public:                                                                  \
        void RunBody();                                                        \
        void SetUp() override {                                                \
            Suite::SetUp();                                                    \
            ::testing::Test::RecordProperty("category", #Category);            \
        }                                                                      \
    };                                                                         \
    TEST_F(Suite##_##Category##_##Name, Run) { RunBody(); }                    \
    void Suite##_##Category##_##Name::RunBody()

class E2eFixture : public ::testing::Test {
protected:
    void SetUp() override;

public:
    fs::path benchmarksDir_;
    fs::path fixturesDir_;
    fs::path projectsDir_; // backward compat alias for benchmarksDir_
    fs::path topoBuildExe_;
    fs::path llvmBinDir_;
    fs::path jvmBenchmarksDir_;

    // Build a project with topo-build.
    //
    // `expectedOutput` (if non-empty) is a path relative to
    // the project directory; when that file exists, the build is skipped
    // and a synthesised success result returned. Used by the benchmark
    // suite to consume pre-built artefacts produced by the
    // `topo_bench_artifacts_build` CTest setup fixture. Empty = legacy
    // inline build.
    RunResult topoBuild(const std::string& projectName,
                        const std::string& expectedOutput = "");

    // Build using Topo-base.toml (all features OFF, swap, build, restore).
    RunResult topoBaseBuild(const std::string& projectName,
                            const std::string& expectedOutput = "");

    // Build using Topo-forced.toml (features force-enabled, swap, build, restore).
    RunResult topoForcedBuild(const std::string& projectName,
                              const std::string& expectedOutput = "");

    // Deprecated: alias for topoBaseBuild().
    RunResult topoBaselineBuild(const std::string& projectName);

    // Build a project with clang++ -O2 (vanilla baseline for B2).
    // Parses Topo.toml to extract sources, include, standard, output.
    RunResult vanillaBuild(const std::string& projectName);

    // Build a shared library with clang++ -O2 -shared (vanilla baseline).
    // Each TU compiled independently with -fvisibility=hidden, then linked.
    RunResult vanillaSharedBuild(const std::string& projectName, const std::vector<std::string>& extraDefines = {});

    // Run a compiled binary from a project's build/ directory.
    RunResult runBinary(const std::string& projectName, const std::string& outputName);

    // Run a JAR file via java -jar.
    RunResult runJar(const std::string& jarPath, const std::vector<std::string>& args = {});

    // Compile a single source file with clang++ and link to a shared library.
    RunResult compileDriver(const std::string& projectName,
                            const std::string& driverSource,
                            const std::string& outputName,
                            const std::vector<std::string>& includeDirs,
                            const std::string& linkLib = "");

    // Flexible output comparison.
    // Supports: {{NUM}} for numeric wildcards, ? prefix for optional lines,
    // ~ prefix for regex lines.
    void assertOutputMatches(const std::string& actual, const std::string& expected);

    // Get file size of a binary in the project's build/ directory.
    uintmax_t getBinarySize(const std::string& projectName, const std::string& outputName);

    // Count exported symbols using llvm-nm.
    int getExportedSymbolCount(const std::string& projectName, const std::string& outputName);

    // Get the full path to a binary (with platform suffix).
    fs::path binaryPath(const std::string& projectName, const std::string& outputName);

    // Get the full path to a shared library (with platform suffix).
    fs::path sharedLibPath(const std::string& projectName, const std::string& outputName);

    // Simple Topo.toml parser — extracts key build fields.
    struct TomlConfig {
        std::vector<std::string> sources;
        std::vector<std::string> include;
        std::string standard = "c++17";
        std::string output;
        std::string outputType; // "executable" or "shared"
    };
    TomlConfig parseTopoToml(const std::string& projectName);
};

} // namespace topo::test::e2e

#endif // TOPO_TEST_E2E_HARNESS_H
