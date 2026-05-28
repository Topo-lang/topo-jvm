#include "E2eHarness.h"

#include "topo/Platform/Process.h"

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <functional>
#include <regex>
#include <string>
#include <vector>

namespace topo::test::e2e {

// topo-bench-artifacts:
// vanilla jar + topo base/auto/forced jars are pre-built by the
// `topo_bench_artifacts_build` CTest setup fixture. Benchmark cases stay
// unchanged at the source level — the per-variant build helpers
// (`topoBuildJvm` / `topoBaseBuildJvm` / `topoForcedBuildJvm` /
// `vanillaJavaBuild`) detect the pre-built JAR on disk and return a
// no-op success. The fallback inline-build path remains for ad-hoc
// `./topo-e2e-jvm-bench` runs outside CTest.

// ============================================================================
// JVM Per-Feature Benchmark Tests
//
// Four-way comparison for opt-in features (mandatory per auto-mode
// semantics):
//   1. vanilla:     javac only (no topo transforms)
//   2. topo base:   topo-build with features OFF   (Topo-base.toml)
//   3. topo auto:   topo-build with features AUTO  (Topo.toml default)
//   4. topo forced: topo-build with features FORCE (Topo-forced.toml)
//
// Three-way / two-way variants exist for genuinely always-on features
// (no auto/force distinction) — see runThreeWay / runTwoWay below.
//
// Each benchmark outputs:
//   RESULT_US_FRIENDLY=<median_us>
//   RESULT_US_UNFRIENDLY=<median_us>
//
// CTest label: "jvm_bench" — excluded from default CI runs.
// ============================================================================

namespace fs = std::filesystem;

// Each mode carries a full BenchStats. Legacy `double`
// fields mirror `.median` (changed from `.mean` to harden against single
// outlier samples — see the seed-determinism fix). Reading code stays the
// same; the underlying number is just more robust.
struct JvmBenchResult {
    double vanilla = 0.0;    // javac only                    (= vanillaStats.median)
    double topoBase = 0.0;   // topo-build, features OFF      (= baseStats.median)
    double topo = 0.0;       // topo-build, auto              (= autoStats.median)
    double topoForced = 0.0; // topo-build, forced            (= forcedStats.median)

    BenchStats vanillaStats;
    BenchStats baseStats;
    BenchStats autoStats;
    BenchStats forcedStats;
};

class JvmPassBench : public E2eFixture {
protected:
    static constexpr double kNoiseFloorUs = 10000.0; // skip below 10ms
    static constexpr int kResampleRuns = 5;
    static constexpr double kAmbiguousMargin = 0.03;

    // ================================================================
    // Build helpers
    // ================================================================

    // Short-circuit on pre-built JARs. `expectedJar` is a
    // path relative to the project directory (e.g. "java_parallel.jar" or
    // "build/vanilla.jar"); when present, skip the topo-build invocation
    // and return synthesised success. Leave the argument empty for legacy
    // behaviour (unconditionally rebuild).
    RunResult topoBuildJvm(const std::string& project,
                           const std::string& expectedJar = "") {
        fs::path projDir = jvmBenchmarksDir_ / project;
        if (!expectedJar.empty()) {
            fs::path candidate = projDir / expectedJar;
            if (fs::exists(candidate)) {
                return RunResult{0, "[prebuilt] " + candidate.generic_string()};
            }
        }
        auto r = platform::runProcessCapture(topoBuildExe_.generic_string(), {}, projDir.generic_string());
        return RunResult{r.exitCode, mergeOutput(r)};
    }

    RunResult topoBaseBuildJvm(const std::string& project,
                               const std::string& expectedJar = "") {
        fs::path projDir = jvmBenchmarksDir_ / project;
        if (!expectedJar.empty()) {
            fs::path candidate = projDir / expectedJar;
            if (fs::exists(candidate)) {
                return RunResult{0, "[prebuilt] " + candidate.generic_string()};
            }
        }

        fs::path topoToml = projDir / "Topo.toml";
        fs::path baseToml = projDir / "Topo-base.toml";
        fs::path saved = projDir / "Topo.toml.saved";

        if (!fs::exists(baseToml)) {
            return RunResult{-1, "Topo-base.toml not found in " + projDir.generic_string()};
        }

        fs::copy_file(topoToml, saved, fs::copy_options::overwrite_existing);
        fs::copy_file(baseToml, topoToml, fs::copy_options::overwrite_existing);

        {
            std::error_code ec;
            fs::remove_all(projDir / ".topo-cache", ec);
        }

        auto result = topoBuildJvm(project);

        fs::copy_file(saved, topoToml, fs::copy_options::overwrite_existing);
        fs::remove(saved);

        return result;
    }

    // Swap Topo.toml -> Topo-forced.toml, build, restore. Mirrors
    // topoBaseBuildJvm above. Cleans .topo-cache before the build so the
    // forced-mode transforms actually run (and do not reuse auto-mode artifacts).
    RunResult topoForcedBuildJvm(const std::string& project,
                                 const std::string& expectedJar = "") {
        fs::path projDir = jvmBenchmarksDir_ / project;
        if (!expectedJar.empty()) {
            fs::path candidate = projDir / expectedJar;
            if (fs::exists(candidate)) {
                return RunResult{0, "[prebuilt] " + candidate.generic_string()};
            }
        }

        fs::path topoToml = projDir / "Topo.toml";
        fs::path forcedToml = projDir / "Topo-forced.toml";
        fs::path saved = projDir / "Topo.toml.saved";

        if (!fs::exists(forcedToml)) {
            return RunResult{-1, "Topo-forced.toml not found in " + projDir.generic_string()};
        }

        fs::copy_file(topoToml, saved, fs::copy_options::overwrite_existing);
        fs::copy_file(forcedToml, topoToml, fs::copy_options::overwrite_existing);

        {
            std::error_code ec;
            fs::remove_all(projDir / ".topo-cache", ec);
        }

        auto result = topoBuildJvm(project);

        fs::copy_file(saved, topoToml, fs::copy_options::overwrite_existing);
        fs::remove(saved);

        return result;
    }

    // Build vanilla JAR: javac + jar without topo transforms.
    //
    // The target artefact is always `build/vanilla.jar`
    // (matching TopoBenchArtifactsDriver's _topo_vanilla_java step). Skip
    // the javac/jar invocations when that JAR already exists.
    RunResult vanillaJavaBuild(const std::string& project) {
        fs::path projDir = jvmBenchmarksDir_ / project;
        {
            fs::path prebuilt = projDir / "build" / "vanilla.jar";
            if (fs::exists(prebuilt)) {
                return RunResult{0, "[prebuilt] " + prebuilt.generic_string()};
            }
        }

        // Resolve java tools
        std::string javac = "javac";
        std::string jarTool = "jar";
        const char* javaHome = std::getenv("JAVA_HOME");
        if (javaHome && std::strlen(javaHome) > 0) {
            fs::path jh(javaHome);
            if (fs::exists(jh / "bin" / "javac")) javac = (jh / "bin" / "javac").generic_string();
            if (fs::exists(jh / "bin" / "jar")) jarTool = (jh / "bin" / "jar").generic_string();
        }

        // Prepare output directory
        fs::path classDir = projDir / "build" / "vanilla-classes";
        {
            std::error_code ec;
            fs::remove_all(classDir, ec);
        }
        fs::create_directories(classDir);

        // Find all .java files under src/main/java
        std::vector<std::string> javaFiles;
        fs::path srcDir = projDir / "src" / "main" / "java";
        for (const auto& entry : fs::recursive_directory_iterator(srcDir)) {
            if (entry.is_regular_file() && entry.path().extension() == ".java") {
                javaFiles.push_back(entry.path().generic_string());
            }
        }

        if (javaFiles.empty()) {
            return RunResult{-1, "No .java files found in " + srcDir.generic_string()};
        }

        // topo-runtime.jar is staged by deploy-topo-runtime-java next to
        // topo-build-jvm-java. TOPO_RUNTIME_JAR_DIR comes from cmake.
        // Fall back to next-to-topo-build for legacy layouts.
        fs::path runtimeJar;
        if (std::string{TOPO_RUNTIME_JAR_DIR}.size() > 0) {
            runtimeJar = fs::path(TOPO_RUNTIME_JAR_DIR) / "topo-runtime.jar";
        } else {
            runtimeJar = topoBuildExe_.parent_path() / "topo-runtime.jar";
        }

        // Compile with javac
        std::vector<std::string> javacArgs;
        javacArgs.push_back("-d");
        javacArgs.push_back(classDir.generic_string());
        javacArgs.push_back("--release");
        javacArgs.push_back("21");
        if (fs::exists(runtimeJar)) {
            javacArgs.push_back("-classpath");
            javacArgs.push_back(runtimeJar.generic_string());
        }
        for (const auto& f : javaFiles)
            javacArgs.push_back(f);

        auto comp = platform::runProcessCapture(javac, javacArgs);
        if (comp.exitCode != 0) {
            return RunResult{comp.exitCode, "javac failed: " + mergeOutput(comp)};
        }

        // Extract runtime classes into class directory so they are bundled
        // into the vanilla JAR (mirrors topo-build-jvm-java Step 6b).
        if (fs::exists(runtimeJar)) {
            std::vector<std::string> extractArgs = {"xf", runtimeJar.generic_string()};
            platform::runProcessCapture(jarTool, extractArgs, classDir.generic_string());
        }

        // Package into JAR
        fs::path buildDir = projDir / "build";
        fs::create_directories(buildDir);
        fs::path jarPath = buildDir / "vanilla.jar";

        std::vector<std::string> jarArgs;
        jarArgs.push_back("--create");
        jarArgs.push_back("--file");
        jarArgs.push_back(jarPath.generic_string());
        jarArgs.push_back("--main-class");
        jarArgs.push_back("app.Main");
        jarArgs.push_back("-C");
        jarArgs.push_back(classDir.generic_string());
        jarArgs.push_back(".");

        auto pkg = platform::runProcessCapture(jarTool, jarArgs);
        return RunResult{pkg.exitCode, mergeOutput(pkg)};
    }

    // Run a JAR with extra JVM args (prepended before -jar).
    RunResult runJvmJarWithArgs(const std::string& project,
                                const std::string& jarRelPath,
                                const std::vector<std::string>& jvmArgs) {
        fs::path jarPath = jvmBenchmarksDir_ / project / jarRelPath;

        std::string javaExe = "java";
        const char* javaHome = std::getenv("JAVA_HOME");
        if (javaHome && std::strlen(javaHome) > 0) {
            fs::path candidate = fs::path(javaHome) / "bin" / "java";
            if (fs::exists(candidate)) javaExe = candidate.generic_string();
        }

        std::vector<std::string> args;
        args.push_back("-ea");
        for (const auto& a : jvmArgs)
            args.push_back(a);
        args.push_back("-jar");
        args.push_back(jarPath.generic_string());

        auto r = platform::runProcessCapture(javaExe, args);
        return RunResult{r.exitCode, mergeOutput(r)};
    }

    // Run a JAR from JVM benchmarks directory.
    // jarPath is relative to the project directory.
    RunResult runJvmJar(const std::string& project, const std::string& jarRelPath) {
        fs::path jarPath = jvmBenchmarksDir_ / project / jarRelPath;
        return runJar(jarPath.generic_string());
    }

    // ================================================================
    // Result extraction and resampling
    // ================================================================

    static double extractResultUs(const std::string& output, const std::string& label) {
        std::string pattern = label + R"(=(\d+\.?\d*))";
        std::regex re(pattern);
        std::smatch match;
        if (std::regex_search(output, match, re)) {
            return std::stod(match[1].str());
        }
        return -1.0;
    }

    double resampleJvmAverage(const std::string& project,
                              const std::string& jarRelPath,
                              const std::string& resultLabel,
                              int runs = kResampleRuns) {
        double sum = 0.0;
        int valid = 0;
        for (int i = 0; i < runs; ++i) {
            auto r = runJvmJar(project, jarRelPath);
            if (r.exitCode == 0) {
                double us = extractResultUs(r.output, resultLabel);
                if (us > 0) {
                    sum += us;
                    ++valid;
                }
            }
        }
        return (valid > 0) ? sum / valid : -1.0;
    }

    // Measure an already-built JAR under variance
    // adaptation. Returns an empty BenchStats (runs=0) on build/run failure
    // paths (caller checks `.runs > 0`). `extraJvmArgs` lets the
    // LoopVectorize benchmark inject `--add-modules jdk.incubator.vector`.
    BenchStats measureJar(const std::string& project,
                          const std::string& jarRelPath,
                          const std::string& resultLabel,
                          const std::vector<std::string>& extraJvmArgs = {}) {
        return measureWithVarianceAdapt([this, project, jarRelPath, resultLabel, extraJvmArgs]() {
            RunResult r;
            if (extraJvmArgs.empty()) {
                r = runJvmJar(project, jarRelPath);
            } else {
                r = runJvmJarWithArgs(project, jarRelPath, extraJvmArgs);
            }
            if (r.exitCode != 0) return -1.0;
            return extractResultUs(r.output, resultLabel);
        });
    }

    // ================================================================
    // Three-way benchmark (vanilla / base / auto)
    //
    // Retained for passes whose `auto` mode is either always-on or
    // identical to `force` (e.g. metadata-only passes such as Obfuscation
    // when used through runTwoWay, or historical leftover benchmarks).
    // For any opt-in feature whose Topo-forced.toml differs meaningfully
    // from Topo.toml, prefer `runFourWay` — it actually measures forced.
    // Per auto-mode semantics, skipping
    // forced here is a violation of the three-mode contract.
    // ================================================================

    JvmBenchResult runThreeWay(const std::string& project,
                               const std::string& vanillaJar,
                               const std::string& baseJar,
                               const std::string& topoJar,
                               const std::string& resultLabel) {
        JvmBenchResult r;

        // Builds first (no-op on the pre-built artefact path).
        bool haveVanilla = false;
        {
            auto build = vanillaJavaBuild(project);
            haveVanilla = (build.exitCode == 0);
        }
        // Pass expected JAR path so the helper short-circuits
        // when the artefact is already present.
        {
            auto build = topoBaseBuildJvm(project, baseJar);
            EXPECT_EQ(build.exitCode, 0) << "Topo-base build failed:\n" << build.output;
            if (build.exitCode != 0) return r;
        }
        {
            std::error_code ec;
            fs::remove_all(jvmBenchmarksDir_ / project / ".topo-cache", ec);
        }
        {
            auto build = topoBuildJvm(project, topoJar);
            EXPECT_EQ(build.exitCode, 0) << "Topo build failed:\n" << build.output;
            if (build.exitCode != 0) return r;
        }

        // Interleaved measurement: vanilla / base / auto in round-robin.
        // Same reproducibility rationale as the LLVM runFourWay.
        auto makeProbe = [this, project, resultLabel](const std::string& jar) -> std::function<double()> {
            if (jar.empty()) return nullptr;
            return [this, project, jar, resultLabel]() {
                auto br = runJvmJar(project, jar);
                if (br.exitCode != 0) return -1.0;
                return extractResultUs(br.output, resultLabel);
            };
        };
        std::vector<std::function<double()>> probes(3);
        probes[0] = haveVanilla ? makeProbe(vanillaJar) : nullptr;
        probes[1] = makeProbe(baseJar);
        probes[2] = makeProbe(topoJar);

        auto sv = measureWithVarianceAdaptInterleaved(probes);
        r.vanillaStats = sv[0];
        r.baseStats    = sv[1];
        r.autoStats    = sv[2];
        r.vanilla  = r.vanillaStats.median;
        r.topoBase = r.baseStats.median;
        r.topo     = r.autoStats.median;

        EXPECT_GT(r.baseStats.runs, 0) << "Topo-base run failed for " << project;
        EXPECT_GT(r.autoStats.runs, 0) << "Topo run failed for " << project;
        return r;
    }

    // ================================================================
    // Four-way benchmark (opt-in features — full three-mode contract)
    //
    // Per auto-mode semantics, opt-in
    // features MUST be measured in all three modes (off/auto/force) plus
    // vanilla javac. `runThreeWay` cheats by skipping forced entirely; this
    // method enforces the contract. Use `runFourWay` for any feature whose
    // Topo-forced.toml differs from Topo.toml — i.e. anything with
    // mode = "auto" in the default config.
    //
    // Failure discipline: this method FAILS LOUDLY if forcedJar is empty —
    // pick `runThreeWay` or `runTwoWay` only when the forced mode is
    // genuinely identical to auto (always-on passes) or intentionally
    // omitted.
    // ================================================================

    JvmBenchResult runFourWay(const std::string& project,
                              const std::string& vanillaJar,
                              const std::string& baseJar,
                              const std::string& autoJar,
                              const std::string& forcedJar,
                              const std::string& resultLabel) {
        // Fail loudly on misuse.
        [&]() {
            ASSERT_FALSE(forcedJar.empty())
                << "runFourWay requires a forced jar path — use runThreeWay or "
                   "runTwoWay if forced is intentionally skipped";
        }();

        JvmBenchResult r;

        // Builds first (no-op on the pre-built artefact path).
        bool haveVanilla = false;
        {
            auto build = vanillaJavaBuild(project);
            haveVanilla = (build.exitCode == 0);
        }
        {
            auto build = topoBaseBuildJvm(project, baseJar);
            EXPECT_EQ(build.exitCode, 0) << "Topo-base build failed:\n" << build.output;
            if (build.exitCode != 0) return r;
        }
        {
            std::error_code ec;
            fs::remove_all(jvmBenchmarksDir_ / project / ".topo-cache", ec);
        }
        {
            auto build = topoBuildJvm(project, autoJar);
            EXPECT_EQ(build.exitCode, 0) << "Topo (auto) build failed:\n" << build.output;
            if (build.exitCode != 0) return r;
        }
        {
            std::error_code ec;
            fs::remove_all(jvmBenchmarksDir_ / project / ".topo-cache", ec);
        }
        {
            auto build = topoForcedBuildJvm(project, forcedJar);
            EXPECT_EQ(build.exitCode, 0) << "Topo-forced build failed:\n" << build.output;
            if (build.exitCode != 0) return r;
        }

        // Interleaved measurement across vanilla/base/auto/forced. Same
        // reproducibility rationale as the LLVM runFourWay: rotating
        // samples spreads transient JVM/OS noise across every probe so
        // run-to-run ratios stay comparable. Median (not mean) drives
        // the legacy doubles so a single outlier sample (GC pause, JIT
        // recompilation, OS context switch) does not skew the ratio
        // that assertions read.
        auto makeProbe = [this, project, resultLabel](const std::string& jar) -> std::function<double()> {
            if (jar.empty()) return nullptr;
            return [this, project, jar, resultLabel]() {
                auto br = runJvmJar(project, jar);
                if (br.exitCode != 0) return -1.0;
                return extractResultUs(br.output, resultLabel);
            };
        };
        std::vector<std::function<double()>> probes(4);
        probes[0] = haveVanilla ? makeProbe(vanillaJar) : nullptr;
        probes[1] = makeProbe(baseJar);
        probes[2] = makeProbe(autoJar);
        probes[3] = makeProbe(forcedJar);

        auto sv = measureWithVarianceAdaptInterleaved(probes);
        r.vanillaStats = sv[0];
        r.baseStats    = sv[1];
        r.autoStats    = sv[2];
        r.forcedStats  = sv[3];
        r.vanilla    = r.vanillaStats.median;
        r.topoBase   = r.baseStats.median;
        r.topo       = r.autoStats.median;
        r.topoForced = r.forcedStats.median;

        EXPECT_GT(r.baseStats.runs, 0)   << "Topo-base run failed for "   << project;
        EXPECT_GT(r.autoStats.runs, 0)   << "Topo (auto) run failed for " << project;
        EXPECT_GT(r.forcedStats.runs, 0) << "Topo-forced run failed for " << project;
        return r;
    }

    // ================================================================
    // Two-way benchmark (always-on features)
    // ================================================================

    JvmBenchResult runTwoWay(const std::string& project,
                             const std::string& vanillaJar,
                             const std::string& topoJar,
                             const std::string& resultLabel) {
        JvmBenchResult r;

        bool haveVanilla = false;
        {
            auto build = vanillaJavaBuild(project);
            haveVanilla = (build.exitCode == 0);
        }
        {
            std::error_code ec;
            fs::remove_all(jvmBenchmarksDir_ / project / ".topo-cache", ec);
        }
        {
            // Pass topoJar so the pre-built artefact fast path
            // short-circuits the build subprocess.
            auto build = topoBuildJvm(project, topoJar);
            EXPECT_EQ(build.exitCode, 0) << "Topo build failed:\n" << build.output;
            if (build.exitCode != 0) return r;
        }

        // Interleaved measurement (always-on: base = topo).
        auto makeProbe = [this, project, resultLabel](const std::string& jar) -> std::function<double()> {
            if (jar.empty()) return nullptr;
            return [this, project, jar, resultLabel]() {
                auto br = runJvmJar(project, jar);
                if (br.exitCode != 0) return -1.0;
                return extractResultUs(br.output, resultLabel);
            };
        };
        std::vector<std::function<double()>> probes(2);
        probes[0] = haveVanilla ? makeProbe(vanillaJar) : nullptr;
        probes[1] = makeProbe(topoJar);

        auto sv = measureWithVarianceAdaptInterleaved(probes);
        r.vanillaStats = sv[0];
        r.autoStats    = sv[1];
        r.vanilla = r.vanillaStats.median;
        r.topo    = r.autoStats.median;
        EXPECT_GT(r.autoStats.runs, 0) << "Topo run failed for " << project;

        r.baseStats = r.autoStats; // always-on: base = topo
        r.topoBase = r.topo;

        return r;
    }

    // ================================================================
    // Reporting
    // ================================================================

    // Benchmark output format. Category is passed explicitly by
    // each benchmark case (via `CATEGORY_BENCH_TEST_F`); PASS/WARN/ERROR
    // labels are produced by `assert<Category>CategoryContract` afterwards.
    static void reportResult(const std::string& feature,
                             const std::string& workload,
                             const JvmBenchResult& r,
                             const char* categoryName = "?",
                             bool alwaysOn = false) {
        const BenchStats& hdr =
            (r.autoStats.runs > 0) ? r.autoStats
                                   : (r.baseStats.runs > 0 ? r.baseStats : r.vanillaStats);
        const char* cvTag = hdr.resampleCapHit ? "resampled to cap" : "stable";

        std::printf("[ BENCH  ] jvm/%s/%s (%s)\n", feature.c_str(), workload.c_str(), categoryName);
        std::printf("           runs: %d (CV=%.3f, %s)\n", hdr.runs, hdr.cv, cvTag);

        auto line = [](const char* label, const BenchStats& s) {
            if (s.runs == 0) {
                std::printf("           %-14s(no samples)\n", label);
            } else {
                std::printf("           %-14s%.0f \u00b1 %.0f us", label, s.mean, s.stdev);
                if (s.resampleCapHit) std::printf("  [CV=%.3f cap-hit]", s.cv);
                std::printf("\n");
            }
        };
        auto lineWithRatio = [](const char* label, const BenchStats& s,
                                const char* ratioName, double num, double den) {
            double ratio = (den > 0.0) ? num / den : 0.0;
            const char* verdict = "PASS"; // real assertion verdict
            if (s.runs == 0) {
                std::printf("           %-14s(no samples)\n", label);
                return;
            }
            std::printf("           %-14s%.0f \u00b1 %.0f us   [%s=%.3f %s]",
                        label, s.mean, s.stdev, ratioName, ratio, verdict);
            if (s.resampleCapHit) std::printf("  [CV=%.3f cap-hit]", s.cv);
            std::printf("\n");
        };

        // Ratios use median — matches what `vanilla` / `topoBase` /
        // `topo` / `topoForced` carry and what the assertion helpers
        // read. Keeps the printed ratio aligned with the verdict.
        line("vanilla:", r.vanillaStats);
        if (alwaysOn) {
            lineWithRatio("topo:", r.autoStats, "topo/vanilla",
                          r.autoStats.median, r.vanillaStats.median);
        } else {
            line("topo base:", r.baseStats);
            lineWithRatio("topo auto:", r.autoStats, "auto/vanilla",
                          r.autoStats.median, r.vanillaStats.median);
            if (r.forcedStats.runs > 0) {
                lineWithRatio("topo forced:", r.forcedStats, "forced/vanilla",
                              r.forcedStats.median, r.vanillaStats.median);
            }
        }
    }

    // ================================================================
    // Assertions
    // ================================================================

    // Topo base (features OFF) should not be slower than vanilla.
    // The transform pipeline itself should be overhead-neutral.
    void assertBaseNotSlowerThanVanilla(const JvmBenchResult& r,
                                        const std::string& feature,
                                        const std::string& workload,
                                        double threshold = 1.05) {
        if (r.vanilla <= 0 || r.topoBase <= 0) return;
        if (r.vanilla < kNoiseFloorUs) {
            std::printf(
                "[  INFO  ]   jvm/%s/%s: skipped base/vanilla "
                "(%.0f us below noise floor)\n",
                feature.c_str(),
                workload.c_str(),
                r.vanilla);
            return;
        }

        double ratio = r.topoBase / r.vanilla;
        EXPECT_LE(ratio, threshold) << "jvm/" << feature << "/" << workload << ": topo base is slower than vanilla.\n"
                                    << "Vanilla:    " << r.vanilla << " us\n"
                                    << "Topo base:  " << r.topoBase << " us\n"
                                    << "Ratio:      " << ratio << " (threshold: " << threshold << ")";
    }

    // Topo (features ON) should not be catastrophically slower than base
    // on unfriendly workloads.
    void assertTopoNotCatastrophic(const JvmBenchResult& r,
                                   const std::string& feature,
                                   const std::string& workload,
                                   double threshold = 1.20) {
        if (r.topoBase <= 0 || r.topo <= 0) return;
        if (r.topoBase == r.topo) return; // always-on
        if (r.topoBase < kNoiseFloorUs) {
            std::printf(
                "[  INFO  ]   jvm/%s/%s: skipped topo/base "
                "(%.0f us below noise floor)\n",
                feature.c_str(),
                workload.c_str(),
                r.topoBase);
            return;
        }

        double ratio = r.topo / r.topoBase;
        EXPECT_LE(ratio, threshold) << "jvm/" << feature << "/" << workload
                                    << ": topo is catastrophically slower than base.\n"
                                    << "Topo base: " << r.topoBase << " us\n"
                                    << "Topo:      " << r.topo << " us\n"
                                    << "Ratio:     " << ratio << " (threshold: " << threshold << ")";
    }

    // Note: functional artifact checks (JAR disassembly / symbol-rename
    // verification) and stdout-equivalence correctness checks live in
    // JvmEquivalenceTests.cpp (benchmark file measures
    // performance only).

    // For always-on features: topo should not be slower than vanilla.
    void assertTopoNotSlowerThanVanilla(const JvmBenchResult& r,
                                        const std::string& feature,
                                        const std::string& workload,
                                        double threshold = 1.05) {
        if (r.vanilla <= 0 || r.topo <= 0) return;
        if (r.vanilla < kNoiseFloorUs) {
            std::printf(
                "[  INFO  ]   jvm/%s/%s: skipped topo/vanilla "
                "(%.0f us below noise floor)\n",
                feature.c_str(),
                workload.c_str(),
                r.vanilla);
            return;
        }

        double ratio = r.topo / r.vanilla;
        EXPECT_LE(ratio, threshold) << "jvm/" << feature << "/" << workload << ": topo is slower than vanilla.\n"
                                    << "Vanilla: " << r.vanilla << " us\n"
                                    << "Topo:    " << r.topo << " us\n"
                                    << "Ratio:   " << ratio << " (threshold: " << threshold << ")";
    }
};

// ---------------------------------------------------------------------------
// JB1: Parallel (opt-in feature)
// ---------------------------------------------------------------------------

// Three-way: vanilla vs topo-base (parallel=off) vs topo (parallel=force).

CATEGORY_BENCH_TEST_F(ENHANCE, JvmPassBench, Parallel_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("parallel",
                        "build/vanilla.jar",
                        "java_parallel_base.jar",
                        "java_parallel.jar",
                        "java_parallel_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("parallel", "friendly", r, "ENHANCE");
    assertEnhanceCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Friendly, "JvmParallelPass");
}

CATEGORY_BENCH_TEST_F(ENHANCE, JvmPassBench, Parallel_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("parallel",
                        "build/vanilla.jar",
                        "java_parallel_base.jar",
                        "java_parallel.jar",
                        "java_parallel_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("parallel", "unfriendly", r, "ENHANCE");
    assertEnhanceCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Unfriendly, "JvmParallelPass");
}

// ---------------------------------------------------------------------------
// JB2: Pipeline (OPT — DAG fork/join rewrite yields measured 1.49× on friendly)
// ---------------------------------------------------------------------------
//
// History: OPT → COVERED → OPT (reverted). The COVERED reclassification
// assumed a ≈ 1.02-1.05× theoretical upper bound; that argument collapsed
// once `config-pipeline-section-not-in-knownsections` was fixed and base
// jars no longer silently shared the rewrite. Post-fix friendly
// measurement: forced/base = 0.667, auto/base = 0.671 — satisfies the OPT
// thresholds cleanly.

CATEGORY_BENCH_TEST_F(ENHANCE, JvmPassBench, Pipeline_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("pipeline",
                        "build/vanilla.jar",
                        "java_pipeline_base.jar",
                        "java_pipeline.jar",
                        "java_pipeline_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("pipeline", "friendly", r, "ENHANCE");
    assertEnhanceCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Friendly, "JvmPipelinePass");
}

CATEGORY_BENCH_TEST_F(ENHANCE, JvmPassBench, Pipeline_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("pipeline",
                        "build/vanilla.jar",
                        "java_pipeline_base.jar",
                        "java_pipeline.jar",
                        "java_pipeline_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("pipeline", "unfriendly", r, "ENHANCE");
    assertEnhanceCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Unfriendly, "JvmPipelinePass");
}

// Note: JVM Stages has no perf benchmark. StageReorderPass is no-op on
// already-ordered user code, and the stage metadata attached to bytecode
// has no consumer in C2 or the transform pipeline — there is nothing for a
// timing benchmark to measure. Functional verification, if it ever
// becomes worthwhile, belongs in a unit test, not the perf suite.

// ---------------------------------------------------------------------------
// JB4: Visibility (always-on)
// ---------------------------------------------------------------------------

CATEGORY_BENCH_TEST_F(COVERED, JvmPassBench, Visibility_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("visibility",
                        "build/vanilla.jar",
                        "java_visibility_base.jar",
                        "java_visibility.jar",
                        "java_visibility_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("visibility", "friendly", r, "COVERED");
    assertCoveredCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Friendly, "JvmVisibilityPass");
}

CATEGORY_BENCH_TEST_F(COVERED, JvmPassBench, Visibility_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("visibility",
                        "build/vanilla.jar",
                        "java_visibility_base.jar",
                        "java_visibility.jar",
                        "java_visibility_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("visibility", "unfriendly", r, "COVERED");
    assertCoveredCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Unfriendly, "JvmVisibilityPass");
}

// ---------------------------------------------------------------------------
// JB5: DataLayout (opt-in feature)
// ---------------------------------------------------------------------------

CATEGORY_BENCH_TEST_F(COVERED, JvmPassBench, DataLayout_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("data_layout",
                        "build/vanilla.jar",
                        "java_data_layout_base.jar",
                        "java_data_layout.jar",
                        "java_data_layout_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("data_layout", "friendly", r, "COVERED");
    assertCoveredCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Friendly, "JvmDataLayoutPass");
}

CATEGORY_BENCH_TEST_F(COVERED, JvmPassBench, DataLayout_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("data_layout",
                        "build/vanilla.jar",
                        "java_data_layout_base.jar",
                        "java_data_layout.jar",
                        "java_data_layout_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("data_layout", "unfriendly", r, "COVERED");
    assertCoveredCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Unfriendly, "JvmDataLayoutPass");
}

// ---------------------------------------------------------------------------
// JB6: LoopVectorize (opt-in feature)
// ---------------------------------------------------------------------------

CATEGORY_BENCH_TEST_F(COVERED, JvmPassBench, LoopVectorize_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    std::vector<std::string> vectorJvmArgs = {"--add-modules", "jdk.incubator.vector"};

    // Hand-rolled four-way: topo auto/forced need --add-modules for the
    // incubator Vector API emitted by LoopVectorizePass. Same interleaved
    // round-robin sampling + median-based ratios as `runFourWay`; the only
    // reason this case can't call `runFourWay` directly is that the auto
    // and forced probes need extra `--add-modules` JVM args.
    JvmBenchResult r;

    bool haveVanilla = false;
    {
        auto build = vanillaJavaBuild("loop_vectorize");
        haveVanilla = (build.exitCode == 0);
    }
    {
        auto build = topoBaseBuildJvm("loop_vectorize", "java_loop_vectorize_base.jar");
        EXPECT_EQ(build.exitCode, 0) << "Topo-base build failed:\n" << build.output;
        if (build.exitCode != 0) return;
    }
    {
        std::error_code ec;
        fs::remove_all(jvmBenchmarksDir_ / "loop_vectorize" / ".topo-cache", ec);
    }
    {
        auto build = topoBuildJvm("loop_vectorize", "java_loop_vectorize.jar");
        EXPECT_EQ(build.exitCode, 0) << "Topo (auto) build failed:\n" << build.output;
        if (build.exitCode != 0) return;
    }
    {
        std::error_code ec;
        fs::remove_all(jvmBenchmarksDir_ / "loop_vectorize" / ".topo-cache", ec);
    }
    {
        auto build = topoForcedBuildJvm("loop_vectorize", "java_loop_vectorize_forced.jar");
        EXPECT_EQ(build.exitCode, 0) << "Topo-forced build failed:\n" << build.output;
        if (build.exitCode != 0) return;
    }

    // Interleaved measurement: vanilla / base / auto+args / forced+args.
    auto makeProbe = [this](const std::string& jar,
                            const std::vector<std::string>& args) -> std::function<double()> {
        if (jar.empty()) return nullptr;
        return [this, jar, args]() {
            RunResult br = args.empty()
                ? runJvmJar("loop_vectorize", jar)
                : runJvmJarWithArgs("loop_vectorize", jar, args);
            if (br.exitCode != 0) return -1.0;
            return extractResultUs(br.output, "RESULT_US_FRIENDLY");
        };
    };
    std::vector<std::function<double()>> probes(4);
    probes[0] = haveVanilla ? makeProbe("build/vanilla.jar", {}) : nullptr;
    probes[1] = makeProbe("java_loop_vectorize_base.jar", {});
    probes[2] = makeProbe("java_loop_vectorize.jar", vectorJvmArgs);
    probes[3] = makeProbe("java_loop_vectorize_forced.jar", vectorJvmArgs);

    auto sv = measureWithVarianceAdaptInterleaved(probes);
    r.vanillaStats = sv[0];
    r.baseStats    = sv[1];
    r.autoStats    = sv[2];
    r.forcedStats  = sv[3];
    r.vanilla    = r.vanillaStats.median;
    r.topoBase   = r.baseStats.median;
    r.topo       = r.autoStats.median;
    r.topoForced = r.forcedStats.median;

    reportResult("loop_vectorize", "friendly", r, "COVERED");
    assertCoveredCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Friendly, "JvmLoopVectorizePass");
}

CATEGORY_BENCH_TEST_F(COVERED, JvmPassBench, LoopVectorize_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("loop_vectorize",
                        "build/vanilla.jar",
                        "java_loop_vectorize_base.jar",
                        "java_loop_vectorize.jar",
                        "java_loop_vectorize_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("loop_vectorize", "unfriendly", r, "COVERED");
    assertCoveredCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Unfriendly, "JvmLoopVectorizePass");
}

// ---------------------------------------------------------------------------
// JB7: Observability (opt-in feature)
// ---------------------------------------------------------------------------

// JVM ObservabilityPass is instrumentation-only: it wraps declared stage
// methods with `dev/topo/Observe$StageEvent` JFR event begin/end bytecode.
// It produces no runtime speedup by design — the injected calls are pure
// overhead; their value is functional (JFR events emitted when JFR is
// enabled). Correctness / artifact checks (bytecode injection, overhead
// bound) live in JvmEquivalenceTests.cpp
// (ObservabilityPass_FunctionalInjection).
//
// The Friendly perf test keeps only the four-way run + reportResult so the
// perf pipeline still exercises the forced variant per the E2E fixture rule
// (only "force"/"off" modes allowed; "auto" triggers VariantBenchmark cost).
CATEGORY_BENCH_TEST_F(INSTRUMENT, JvmPassBench, Observability_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("observability",
                        "build/vanilla.jar",
                        "java_observability_base.jar",
                        "java_observability.jar",
                        "java_observability_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("observability", "friendly", r, "INSTRUMENT");
    assertInstrumentCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                     Workload::Friendly, "JvmObservabilityPass");
}

CATEGORY_BENCH_TEST_F(INSTRUMENT, JvmPassBench, Observability_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("observability",
                        "build/vanilla.jar",
                        "java_observability_base.jar",
                        "java_observability.jar",
                        "java_observability_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("observability", "unfriendly", r, "INSTRUMENT");
    assertInstrumentCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                     Workload::Unfriendly, "JvmObservabilityPass");
}

// ---------------------------------------------------------------------------
// JB8: Adaptive (opt-in feature)
// ---------------------------------------------------------------------------

CATEGORY_BENCH_TEST_F(RUNTIME, JvmPassBench, Adaptive_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("adaptive",
                        "build/vanilla.jar",
                        "java_adaptive_base.jar",
                        "java_adaptive.jar",
                        "java_adaptive_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("adaptive", "friendly", r, "RUNTIME");
    assertRuntimeCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Friendly, "JvmAdaptivePass");
}

CATEGORY_BENCH_TEST_F(RUNTIME, JvmPassBench, Adaptive_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("adaptive",
                        "build/vanilla.jar",
                        "java_adaptive_base.jar",
                        "java_adaptive.jar",
                        "java_adaptive_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("adaptive", "unfriendly", r, "RUNTIME");
    assertRuntimeCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Unfriendly, "JvmAdaptivePass");
}

// ---------------------------------------------------------------------------
// JB9: Obfuscation (two-way — no base variant)
// ---------------------------------------------------------------------------

// JVM ObfuscationPass is metadata-only (symbol renaming): private methods
// in user classes are remapped to `_t<hex>` hashed names via ASM's
// ClassRemapper. It produces no runtime speedup by design — renamed
// methods execute identically to the originals; the value is functional
// (symbol obfuscation in the shipped JAR). Correctness / artifact checks
// (disassembly verification of the rename) live in JvmEquivalenceTests.cpp
// (ObfuscationPass_FunctionalRename).
//
// The Friendly perf test keeps only the four-way run + reportResult so the
// perf pipeline still exercises the forced variant per the E2E fixture rule
// (only "force"/"off" modes allowed; "auto" triggers VariantBenchmark cost).
CATEGORY_BENCH_TEST_F(INSTRUMENT, JvmPassBench, Obfuscation_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("obfuscation",
                        "build/vanilla.jar",
                        "java_obfuscation_base.jar",
                        "java_obfuscation.jar",
                        "java_obfuscation_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("obfuscation", "friendly", r, "INSTRUMENT");
    assertInstrumentCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                     Workload::Friendly, "JvmObfuscationPass");
}

CATEGORY_BENCH_TEST_F(INSTRUMENT, JvmPassBench, Obfuscation_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("obfuscation",
                        "build/vanilla.jar",
                        "java_obfuscation_base.jar",
                        "java_obfuscation.jar",
                        "java_obfuscation_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("obfuscation", "unfriendly", r, "INSTRUMENT");
    assertInstrumentCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                     Workload::Unfriendly, "JvmObfuscationPass");
}

// ---------------------------------------------------------------------------
// JB10: Lifetime (opt-in feature)
// ---------------------------------------------------------------------------

CATEGORY_BENCH_TEST_F(ENHANCE, JvmPassBench, Lifetime_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("lifetime",
                        "build/vanilla.jar",
                        "java_lifetime_base.jar",
                        "java_lifetime.jar",
                        "java_lifetime_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("lifetime", "friendly", r, "ENHANCE");
    assertEnhanceCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Friendly, "JvmArenaPass");
}

CATEGORY_BENCH_TEST_F(ENHANCE, JvmPassBench, Lifetime_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("lifetime",
                        "build/vanilla.jar",
                        "java_lifetime_base.jar",
                        "java_lifetime.jar",
                        "java_lifetime_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("lifetime", "unfriendly", r, "ENHANCE");
    assertEnhanceCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Unfriendly, "JvmArenaPass");
}

// ---------------------------------------------------------------------------
// JB11: TypeNarrowing (opt-in feature)
// ---------------------------------------------------------------------------

CATEGORY_BENCH_TEST_F(COVERED, JvmPassBench, TypeNarrowing_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("type_narrowing",
                        "build/vanilla.jar",
                        "java_type_narrowing_base.jar",
                        "java_type_narrowing.jar",
                        "java_type_narrowing_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("type_narrowing", "friendly", r, "COVERED");
    assertCoveredCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Friendly, "JvmTypeNarrowingPass");
}

CATEGORY_BENCH_TEST_F(COVERED, JvmPassBench, TypeNarrowing_Unfriendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("type_narrowing",
                        "build/vanilla.jar",
                        "java_type_narrowing_base.jar",
                        "java_type_narrowing.jar",
                        "java_type_narrowing_forced.jar",
                        "RESULT_US_UNFRIENDLY");
    reportResult("type_narrowing", "unfriendly", r, "COVERED");
    assertCoveredCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Unfriendly, "JvmTypeNarrowingPass");
}

// ---------------------------------------------------------------------------
// JB12: Prefetch (opt-in feature)
// ---------------------------------------------------------------------------

CATEGORY_BENCH_TEST_F(ENHANCE, JvmPassBench, Prefetch_Friendly) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto r = runFourWay("prefetch",
                        "build/vanilla.jar",
                        "java_prefetch_base.jar",
                        "java_prefetch.jar",
                        "java_prefetch_forced.jar",
                        "RESULT_US_FRIENDLY");
    reportResult("prefetch", "friendly", r, "ENHANCE");
    assertEnhanceCategoryContract(r.vanillaStats, r.baseStats, r.autoStats, r.forcedStats,
                                  Workload::Friendly, "JvmPrefetchPass");
}

// Note: JVM Prefetch has no Unfriendly benchmark. The unfriendly workload is
// `access(random)`, which PrefetchPass explicitly skips by design (prefetch
// hurts random access). Base/auto/forced bytecode is identical on the
// PrefetchPass axis, and random-access cache-miss patterns produce CV > 0.3
// — the ENHANCE non-regression band is untrustworthy in that regime. The
// equivalence layer `PrefetchPass_DefaultMatchesVanilla` already covers the
// "pass skips random methods" assertion at the bytecode level.

} // namespace topo::test::e2e
