#include "E2eHarness.h"

#include "topo/Platform/Process.h"

#include <algorithm>
#include <cstring>
#include <filesystem>
#include <iterator>
#include <regex>
#include <sstream>
#include <string>
#include <vector>

namespace topo::test::e2e {

// ============================================================================
// JVM Behavioral Equivalence Tests
//
// For each ASM bytecode pass: build vanilla Java (javac only, no transforms)
// + build with topo (javac + ASM transforms) + run both JARs + compare
// stdout after stripping timing lines.
//
// 15 passes x {Default, Base, Forced} modes = up to 39 tests.
// C2-covered passes (TypeNarrowing, LoopVectorize, Prefetch) get a single
// Default sanity test instead of the full triple.
//
// Test naming convention:
//   <PassName>_<Mode>MatchesVanilla
//
// CTest label: "jvm_equivalence"
// ============================================================================

namespace fs = std::filesystem;

class JvmEquivalence : public E2eFixture {
protected:
    // ================================================================
    // Build helpers
    // ================================================================

    RunResult topoBuildJvm(const std::string& project) {
        fs::path projDir = jvmBenchmarksDir_ / project;
        auto r = platform::runProcessCapture(topoBuildExe_.generic_string(), {}, projDir.generic_string());
        return RunResult{r.exitCode, mergeOutput(r)};
    }

    RunResult topoBaseBuildJvm(const std::string& project) {
        fs::path projDir = jvmBenchmarksDir_ / project;
        TopoTomlSwap swap(projDir, "Topo-base.toml");
        if (!swap.engaged()) {
            return RunResult{-1, swap.error()};
        }

        {
            std::error_code ec;
            fs::remove_all(projDir / ".topo-cache", ec);
        }

        // Swap restored automatically when this scope exits — even on
        // throw or ASSERT-driven early return inside topoBuildJvm.
        return topoBuildJvm(project);
    }

    RunResult topoForcedBuildJvm(const std::string& project) {
        fs::path projDir = jvmBenchmarksDir_ / project;
        TopoTomlSwap swap(projDir, "Topo-forced.toml");
        if (!swap.engaged()) {
            return RunResult{-1, swap.error()};
        }

        {
            std::error_code ec;
            fs::remove_all(projDir / ".topo-cache", ec);
        }

        return topoBuildJvm(project);
    }

    // Build vanilla JAR: javac + jar without topo transforms.
    RunResult vanillaJavaBuild(const std::string& project) {
        fs::path projDir = jvmBenchmarksDir_ / project;

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

        // Locate topo-runtime.jar next to the topo-build executable
        fs::path runtimeJar = topoBuildExe_.parent_path() / "topo-runtime.jar";

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
            return RunResult{comp.exitCode, "javac failed: " + comp.stdoutOutput};
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
        return RunResult{pkg.exitCode, pkg.stdoutOutput};
    }

    // Run a JAR from JVM benchmarks directory.
    // jarRelPath is relative to the project directory; checks project root
    // first, then build/ subdirectory.
    RunResult runJvmJar(const std::string& project, const std::string& jarRelPath) {
        fs::path jarPath = jvmBenchmarksDir_ / project / jarRelPath;
        if (!fs::exists(jarPath)) {
            jarPath = jvmBenchmarksDir_ / project / "build" / jarRelPath;
        }
        return runJar(jarPath.generic_string());
    }

    // ================================================================
    // Output stripping helpers
    // ================================================================

    // Strip timing-dependent and benchmark-summary lines from output.
    //
    // Lines stripped (inherently non-deterministic across runs):
    //
    //   1. RESULT_US_* prefixed lines      -- wall-clock microseconds per phase
    //   2. "Stabilization time: <N> ms"    -- adaptive monitor warm-up time
    //   3. Any line containing "<N> ns/us/ms" -- benchmark timing reports
    //   4. Lines containing "friendly=" -- benchmark workload summary values
    //      (accumulated floating-point results that differ due to FP
    //      accumulation ordering when transforms change memory layout or
    //      execution order). The real correctness indicator is the
    //      "all assertions passed" line from each benchmark's internal checks.
    static std::string stripTimingLines(const std::string& output) {
        static const std::regex unitRegex(R"(\b\d+(\.\d+)?\s+(ns|us|ms|µs)\b)",
                                          std::regex::ECMAScript);
        static const std::regex stabRegex(R"(^\s*Stabilization time:\s*\d+\s*ms\b)",
                                          std::regex::ECMAScript);
        static const std::regex benchSummaryRegex(R"(\bfriendly=[\d.Ee+-]+)",
                                                  std::regex::ECMAScript);

        std::istringstream iss(output);
        std::string line;
        std::string result;
        while (std::getline(iss, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();

            // 1. RESULT_US_* prefix
            if (line.rfind("RESULT_US", 0) == 0) continue;

            // 2. Stabilization time
            if (std::regex_search(line, stabRegex)) continue;

            // 3. "<N> ns/us/ms" anywhere on the line
            if (std::regex_search(line, unitRegex)) continue;

            // 4. Benchmark summary with "friendly=<float>"
            if (std::regex_search(line, benchSummaryRegex)) continue;

            if (!result.empty()) result += "\n";
            result += line;
        }
        return result;
    }

    // Strip JSON trace event lines emitted by ObservabilityPass instrumentation.
    static std::string stripObservabilityNoise(const std::string& output) {
        static const std::regex traceRegex(R"(^\s*\{"name":)", std::regex::ECMAScript);
        std::istringstream iss(output);
        std::string line;
        std::string result;
        while (std::getline(iss, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (std::regex_search(line, traceRegex)) continue;
            if (!result.empty()) result += "\n";
            result += line;
        }
        return result;
    }

    // ================================================================
    // Equivalence assertion helpers
    // ================================================================

    // Vanilla vs topo default build (Topo.toml).
    static void assertJvmDefaultEquivalence(JvmEquivalence* self,
                                            const std::string& project,
                                            const std::string& topoJarName) {
        auto vanilla = self->vanillaJavaBuild(project);
        ASSERT_EQ(vanilla.exitCode, 0) << "Vanilla build failed:\n" << vanilla.output;

        auto topo = self->topoBuildJvm(project);
        ASSERT_EQ(topo.exitCode, 0) << "Topo build failed:\n" << topo.output;

        auto vanillaRun = self->runJvmJar(project, "build/vanilla.jar");
        ASSERT_EQ(vanillaRun.exitCode, 0) << "Vanilla run failed:\n" << vanillaRun.output;

        auto topoRun = self->runJvmJar(project, topoJarName);
        ASSERT_EQ(topoRun.exitCode, 0) << "Topo run failed:\n" << topoRun.output;

        EXPECT_EQ(stripTimingLines(vanillaRun.output), stripTimingLines(topoRun.output))
            << "Semantic divergence detected (default mode)!\n"
            << "Vanilla output:\n"
            << vanillaRun.output << "\n"
            << "Topo output:\n"
            << topoRun.output;
    }

    // Vanilla vs topo base build (Topo-base.toml, features OFF).
    static void assertJvmBaseEquivalence(JvmEquivalence* self,
                                         const std::string& project,
                                         const std::string& topoBaseJarName) {
        auto vanilla = self->vanillaJavaBuild(project);
        ASSERT_EQ(vanilla.exitCode, 0) << "Vanilla build failed:\n" << vanilla.output;

        auto topo = self->topoBaseBuildJvm(project);
        ASSERT_EQ(topo.exitCode, 0) << "Topo base build failed:\n" << topo.output;

        auto vanillaRun = self->runJvmJar(project, "build/vanilla.jar");
        ASSERT_EQ(vanillaRun.exitCode, 0) << "Vanilla run failed:\n" << vanillaRun.output;

        auto topoRun = self->runJvmJar(project, topoBaseJarName);
        ASSERT_EQ(topoRun.exitCode, 0) << "Topo base run failed:\n" << topoRun.output;

        EXPECT_EQ(stripTimingLines(vanillaRun.output), stripTimingLines(topoRun.output))
            << "Pass-off (base) mode broke semantics!\n"
            << "Vanilla output:\n"
            << vanillaRun.output << "\n"
            << "Topo base output:\n"
            << topoRun.output;
    }

    // ================================================================
    // Functional artifact helpers
    //
    // Used by tests that inspect the shipped JAR bytecode directly to
    // verify a pass actually fired (e.g. ObservabilityPass JFR event
    // injection, ObfuscationPass symbol rename).
    // ================================================================

    // Extract every entry path (`jar -tf <jar>`) from a JAR.
    std::string jarListEntries(const std::string& project, const std::string& jarRelPath) {
        fs::path jarPath = jvmBenchmarksDir_ / project / jarRelPath;
        if (!fs::exists(jarPath)) {
            jarPath = jvmBenchmarksDir_ / project / "build" / jarRelPath;
        }
        if (!fs::exists(jarPath)) return "";

        std::string jarTool = "jar";
        const char* javaHome = std::getenv("JAVA_HOME");
        if (javaHome && std::strlen(javaHome) > 0) {
            fs::path cand = fs::path(javaHome) / "bin" / "jar";
            if (fs::exists(cand)) jarTool = cand.generic_string();
        }

        auto r = platform::runProcessCapture(jarTool, {"-tf", jarPath.generic_string()});
        if (r.exitCode != 0) return "";
        return r.stdoutOutput;
    }

    // Disassemble all user `.class` entries (under app/) in a JAR using
    // `javap -c -p`. `-p` exposes private members (for structural assertions
    // like the obfuscation rename check), and `-c` emits bytecode
    // instructions so tests can also assert on in-method references (e.g.
    // ObservabilityPass's `NEW dev/topo/Observe$StageEvent` call-site
    // injection, which has no field or signature footprint and therefore
    // is invisible to `javap -p` alone). Skips runtime classes
    // (dev/topo/...) since they keep their original names by design.
    std::string javapDumpJar(const std::string& project, const std::string& jarRelPath) {
        fs::path jarPath = jvmBenchmarksDir_ / project / jarRelPath;
        if (!fs::exists(jarPath)) {
            jarPath = jvmBenchmarksDir_ / project / "build" / jarRelPath;
        }
        if (!fs::exists(jarPath)) return "";

        std::string javapTool = "javap";
        const char* javaHome = std::getenv("JAVA_HOME");
        if (javaHome && std::strlen(javaHome) > 0) {
            fs::path cand = fs::path(javaHome) / "bin" / "javap";
            if (fs::exists(cand)) javapTool = cand.generic_string();
        }

        // Collect all user class entries.
        std::vector<std::string> entries;
        {
            auto list = jarListEntries(project, jarRelPath);
            std::istringstream iss(list);
            std::string line;
            while (std::getline(iss, line)) {
                if (!line.empty() && line.back() == '\r') line.pop_back();
                // Only user classes: skip dev/topo runtime classes.
                if (line.size() > 6 && line.substr(line.size() - 6) == ".class"
                    && line.rfind("app/", 0) == 0) {
                    entries.push_back(line);
                }
            }
        }
        if (entries.empty()) return "";

        std::vector<std::string> args = {"-c", "-p", "--class-path", jarPath.generic_string()};
        for (const auto& e : entries) {
            std::string fqn = e.substr(0, e.size() - 6);
            std::replace(fqn.begin(), fqn.end(), '/', '.');
            args.push_back(fqn);
        }
        auto r = platform::runProcessCapture(javapTool, args);
        if (r.exitCode != 0) return "";
        return r.stdoutOutput;
    }

    static double extractResultUs(const std::string& output, const std::string& label) {
        std::string pattern = label + R"(=(\d+\.?\d*))";
        std::regex re(pattern);
        std::smatch match;
        if (std::regex_search(output, match, re)) {
            return std::stod(match[1].str());
        }
        return -1.0;
    }

    // Vanilla vs topo forced build (Topo-forced.toml, features force-on).
    static void assertJvmForcedEquivalence(JvmEquivalence* self,
                                           const std::string& project,
                                           const std::string& topoForcedJarName) {
        auto vanilla = self->vanillaJavaBuild(project);
        ASSERT_EQ(vanilla.exitCode, 0) << "Vanilla build failed:\n" << vanilla.output;

        auto topo = self->topoForcedBuildJvm(project);
        ASSERT_EQ(topo.exitCode, 0) << "Topo forced build failed:\n" << topo.output;

        auto vanillaRun = self->runJvmJar(project, "build/vanilla.jar");
        ASSERT_EQ(vanillaRun.exitCode, 0) << "Vanilla run failed:\n" << vanillaRun.output;

        auto topoRun = self->runJvmJar(project, topoForcedJarName);
        ASSERT_EQ(topoRun.exitCode, 0) << "Topo forced run failed:\n" << topoRun.output;

        EXPECT_EQ(stripTimingLines(vanillaRun.output), stripTimingLines(topoRun.output))
            << "Pass-forced mode broke semantics!\n"
            << "Vanilla output:\n"
            << vanillaRun.output << "\n"
            << "Topo forced output:\n"
            << topoRun.output;
    }
};

// ============================================================================
// Group 1: VisibilityPass -> visibility benchmark
// ============================================================================

TEST_F(JvmEquivalence, VisibilityPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "visibility", "java_visibility.jar");
}

TEST_F(JvmEquivalence, VisibilityPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "visibility", "java_visibility_base.jar");
}

TEST_F(JvmEquivalence, VisibilityPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "visibility", "java_visibility_forced.jar");
}

// ============================================================================
// Group 2: StaticPromotionPass -> visibility benchmark (shared)
//
// StaticPromotionPass is opt>=1 and always active in dev-mode builds.
// Shares the visibility benchmark since access-flag modifications are
// exercised by the same workload.
// ============================================================================

TEST_F(JvmEquivalence, StaticPromotionPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "visibility", "java_visibility.jar");
}

TEST_F(JvmEquivalence, StaticPromotionPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "visibility", "java_visibility_base.jar");
}

TEST_F(JvmEquivalence, StaticPromotionPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "visibility", "java_visibility_forced.jar");
}

// ============================================================================
// Group 3: InlineHintPass -> visibility benchmark (shared)
//
// InlineHintPass adds ACC_FINAL to assist JIT devirtualization. Shares the
// visibility benchmark since the same access-flag workload exercises it.
// ============================================================================

TEST_F(JvmEquivalence, InlineHintPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "visibility", "java_visibility.jar");
}

TEST_F(JvmEquivalence, InlineHintPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "visibility", "java_visibility_base.jar");
}

TEST_F(JvmEquivalence, InlineHintPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "visibility", "java_visibility_forced.jar");
}

// ============================================================================
// Group 4: ReturnSpecializationPass -> data_layout benchmark (shared)
// ============================================================================

TEST_F(JvmEquivalence, ReturnSpecializationPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "data_layout", "java_data_layout.jar");
}

TEST_F(JvmEquivalence, ReturnSpecializationPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "data_layout", "java_data_layout_base.jar");
}

TEST_F(JvmEquivalence, ReturnSpecializationPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "data_layout", "java_data_layout_forced.jar");
}

// ============================================================================
// Group 5: DataLayoutPass -> data_layout benchmark
// ============================================================================

TEST_F(JvmEquivalence, DataLayoutPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "data_layout", "java_data_layout.jar");
}

TEST_F(JvmEquivalence, DataLayoutPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "data_layout", "java_data_layout_base.jar");
}

TEST_F(JvmEquivalence, DataLayoutPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "data_layout", "java_data_layout_forced.jar");
}

// ============================================================================
// Group 6: TypeNarrowingPass -> type_narrowing (C2-covered, sanity only)
//
// Forced variant added to replace the stdout-equivalence
// correctness check that previously lived inline in JvmBenchmarkTests.cpp.
// C2 speculative devirt already covers Array.get() interface dispatch, so the
// JVM bench asserts non-regression only; semantic equivalence against vanilla
// is verified here for both Default (auto) and Forced modes.
// ============================================================================

TEST_F(JvmEquivalence, TypeNarrowingPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "type_narrowing", "java_type_narrowing.jar");
}

TEST_F(JvmEquivalence, TypeNarrowingPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "type_narrowing", "java_type_narrowing_forced.jar");
}

// ============================================================================
// Group 7: ParallelPass -> parallel benchmark
// ============================================================================

TEST_F(JvmEquivalence, ParallelPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "parallel", "java_parallel.jar");
}

TEST_F(JvmEquivalence, ParallelPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "parallel", "java_parallel_base.jar");
}

TEST_F(JvmEquivalence, ParallelPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "parallel", "java_parallel_forced.jar");
}

// ============================================================================
// Group 8: LoopVectorizePass -> loop_vectorize (C2-covered, sanity only)
// ============================================================================

TEST_F(JvmEquivalence, LoopVectorizePass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "loop_vectorize", "java_loop_vectorize.jar");
}

// ============================================================================
// Group 9: PipelinePass -> pipeline benchmark
// ============================================================================

TEST_F(JvmEquivalence, PipelinePass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "pipeline", "java_pipeline.jar");
}

TEST_F(JvmEquivalence, PipelinePass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "pipeline", "java_pipeline_base.jar");
}

TEST_F(JvmEquivalence, PipelinePass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "pipeline", "java_pipeline_forced.jar");
}

// ============================================================================
// Group 10: PrefetchPass -> prefetch (C2/HW-covered, sanity only)
// ============================================================================

TEST_F(JvmEquivalence, PrefetchPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "prefetch", "java_prefetch.jar");
}

// ============================================================================
// Group 11: ObservabilityPass -> observability benchmark
//
// ObservabilityPass injects tracing instrumentation that emits JSON trace
// events as side-effect output. The equivalence dimension we care about is
// "functional output (program-printed lines, not trace events) is unchanged
// when instrumentation is added". Both stripTimingLines AND
// stripObservabilityNoise are applied before comparison.
// ============================================================================

TEST_F(JvmEquivalence, ObservabilityPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto vanilla = vanillaJavaBuild("observability");
    ASSERT_EQ(vanilla.exitCode, 0) << "Vanilla build failed:\n" << vanilla.output;

    auto topo = topoBuildJvm("observability");
    ASSERT_EQ(topo.exitCode, 0) << "Topo build failed:\n" << topo.output;

    auto vanillaRun = runJvmJar("observability", "build/vanilla.jar");
    ASSERT_EQ(vanillaRun.exitCode, 0) << "Vanilla run failed:\n" << vanillaRun.output;

    auto topoRun = runJvmJar("observability", "java_observability.jar");
    ASSERT_EQ(topoRun.exitCode, 0) << "Topo run failed:\n" << topoRun.output;

    EXPECT_EQ(stripObservabilityNoise(stripTimingLines(vanillaRun.output)),
              stripObservabilityNoise(stripTimingLines(topoRun.output)))
        << "ObservabilityPass changed program functional output between vanilla "
           "and default modes (excluding trace events)";
}

TEST_F(JvmEquivalence, ObservabilityPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto vanilla = vanillaJavaBuild("observability");
    ASSERT_EQ(vanilla.exitCode, 0) << "Vanilla build failed:\n" << vanilla.output;

    auto topo = topoBaseBuildJvm("observability");
    ASSERT_EQ(topo.exitCode, 0) << "Topo base build failed:\n" << topo.output;

    auto vanillaRun = runJvmJar("observability", "build/vanilla.jar");
    ASSERT_EQ(vanillaRun.exitCode, 0) << "Vanilla run failed:\n" << vanillaRun.output;

    auto topoRun = runJvmJar("observability", "java_observability_base.jar");
    ASSERT_EQ(topoRun.exitCode, 0) << "Topo base run failed:\n" << topoRun.output;

    EXPECT_EQ(stripObservabilityNoise(stripTimingLines(vanillaRun.output)),
              stripObservabilityNoise(stripTimingLines(topoRun.output)))
        << "ObservabilityPass changed program functional output between vanilla "
           "and base modes (excluding trace events)";
}

TEST_F(JvmEquivalence, ObservabilityPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto vanilla = vanillaJavaBuild("observability");
    ASSERT_EQ(vanilla.exitCode, 0) << "Vanilla build failed:\n" << vanilla.output;

    auto topo = topoForcedBuildJvm("observability");
    ASSERT_EQ(topo.exitCode, 0) << "Topo forced build failed:\n" << topo.output;

    auto vanillaRun = runJvmJar("observability", "build/vanilla.jar");
    ASSERT_EQ(vanillaRun.exitCode, 0) << "Vanilla run failed:\n" << vanillaRun.output;

    auto topoRun = runJvmJar("observability", "java_observability_forced.jar");
    ASSERT_EQ(topoRun.exitCode, 0) << "Topo forced run failed:\n" << topoRun.output;

    EXPECT_EQ(stripObservabilityNoise(stripTimingLines(vanillaRun.output)),
              stripObservabilityNoise(stripTimingLines(topoRun.output)))
        << "ObservabilityPass changed program functional output between vanilla "
           "and forced modes (excluding trace events)";
}

// ============================================================================
// Group 12: AdaptivePass -> adaptive benchmark
// ============================================================================

TEST_F(JvmEquivalence, AdaptivePass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "adaptive", "java_adaptive.jar");
}

TEST_F(JvmEquivalence, AdaptivePass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "adaptive", "java_adaptive_base.jar");
}

TEST_F(JvmEquivalence, AdaptivePass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "adaptive", "java_adaptive_forced.jar");
}

// ============================================================================
// Group 13: ArenaPass -> lifetime benchmark
// ============================================================================

TEST_F(JvmEquivalence, ArenaPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "lifetime", "java_lifetime.jar");
}

TEST_F(JvmEquivalence, ArenaPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "lifetime", "java_lifetime_base.jar");
}

TEST_F(JvmEquivalence, ArenaPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "lifetime", "java_lifetime_forced.jar");
}

// ============================================================================
// Group 14: StageReorderPass -> stages benchmark
// ============================================================================

TEST_F(JvmEquivalence, StageReorderPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "stages", "java_stages.jar");
}

TEST_F(JvmEquivalence, StageReorderPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "stages", "java_stages_base.jar");
}

TEST_F(JvmEquivalence, StageReorderPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "stages", "java_stages_forced.jar");
}

// ============================================================================
// Group 15: ObfuscationPass -> obfuscation benchmark
// ============================================================================

TEST_F(JvmEquivalence, ObfuscationPass_DefaultMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmDefaultEquivalence(this, "obfuscation", "java_obfuscation.jar");
}

TEST_F(JvmEquivalence, ObfuscationPass_BaseMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmBaseEquivalence(this, "obfuscation", "java_obfuscation_base.jar");
}

TEST_F(JvmEquivalence, ObfuscationPass_ForcedMatchesVanilla) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    assertJvmForcedEquivalence(this, "obfuscation", "java_obfuscation_forced.jar");
}

// ============================================================================
// Functional artifact checks migrated from JvmBenchmarkTests.cpp
//
// The TEST_F bodies below inspect the produced JAR via javap and assert that
// the relevant pass actually fired. Benchmark files keep only the four-way
// perf measurement + reportResult — benchmark files measure
// performance only; artifact checks live here.
// ============================================================================

// --- ObservabilityPass (JFR StageEvent injection) ---
//
// Functional assertions:
//   - Topo JAR user class (app.Main) references `dev/topo/Observe$StageEvent`;
//     vanilla JAR does not.
//   - Overhead upper bound: `topo/vanilla < 1.10` (relaxed — one-shot ratio
//     has +/-5% noise band on this harness).
TEST_F(JvmEquivalence, ObservabilityPass_FunctionalInjection) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto vBuild = vanillaJavaBuild("observability");
    ASSERT_EQ(vBuild.exitCode, 0) << "Vanilla build failed:\n" << vBuild.output;
    auto vanillaRun = runJvmJar("observability", "build/vanilla.jar");
    ASSERT_EQ(vanillaRun.exitCode, 0) << "Vanilla run failed:\n" << vanillaRun.output;
    double vanillaUs = extractResultUs(vanillaRun.output, "RESULT_US_FRIENDLY");

    auto tBuild = topoBuildJvm("observability");
    ASSERT_EQ(tBuild.exitCode, 0) << "Topo build failed:\n" << tBuild.output;
    auto topoRun = runJvmJar("observability", "java_observability.jar");
    ASSERT_EQ(topoRun.exitCode, 0) << "Topo run failed:\n" << topoRun.output;
    double topoUs = extractResultUs(topoRun.output, "RESULT_US_FRIENDLY");

    // 1. Bytecode injection check.
    std::string topoDump = javapDumpJar("observability", "java_observability.jar");
    std::string vanillaDump = javapDumpJar("observability", "build/vanilla.jar");
    EXPECT_FALSE(topoDump.empty()) << "javap on topo JAR produced no output";

    bool topoHasStageEvent = topoDump.find("dev/topo/Observe$StageEvent") != std::string::npos
                             || topoDump.find("dev.topo.Observe$StageEvent") != std::string::npos;
    bool vanillaHasStageEvent = vanillaDump.find("dev/topo/Observe$StageEvent") != std::string::npos
                                || vanillaDump.find("dev.topo.Observe$StageEvent") != std::string::npos;

    EXPECT_TRUE(topoHasStageEvent) << "observability: topo JAR missing StageEvent references — "
                                   << "ObservabilityPass did not instrument app.Main.";
    EXPECT_FALSE(vanillaHasStageEvent)
        << "observability: vanilla JAR unexpectedly references StageEvent "
        << "(should only appear after ObservabilityPass transform).";

    // 2. Overhead upper bound (JFR off at runtime — pure call-site cost).
    constexpr double kNoiseFloorUs = 10000.0;
    if (vanillaUs > 0 && topoUs > 0 && vanillaUs >= kNoiseFloorUs) {
        double ratio = topoUs / vanillaUs;
        EXPECT_LE(ratio, 1.10)
            << "observability: topo/vanilla overhead " << ratio
            << " exceeds 1.10 upper bound (vanilla=" << vanillaUs
            << "us, topo=" << topoUs << "us)";
        std::printf("[  INFO  ] jvm/observability: overhead topo/vanilla = %.3f\n", ratio);
    }
    std::printf("[  INFO  ] jvm/observability: topo has StageEvent=%d, vanilla has=%d\n",
                topoHasStageEvent, vanillaHasStageEvent);
}

// --- ObfuscationPass (symbol rename) ---
//
// Functional assertion: the topo JAR's app.Main disassembly must contain
// at least one `_t[0-9a-f]+` renamed method and must NOT contain the
// original private names (`internalHelper`, `secretCompute`). The public
// `run`/`main` method names are preserved by design.
TEST_F(JvmEquivalence, ObfuscationPass_FunctionalRename) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto vBuild = vanillaJavaBuild("obfuscation");
    ASSERT_EQ(vBuild.exitCode, 0) << "Vanilla build failed:\n" << vBuild.output;

    auto tBuild = topoBuildJvm("obfuscation");
    ASSERT_EQ(tBuild.exitCode, 0) << "Topo build failed:\n" << tBuild.output;

    std::string topoDump = javapDumpJar("obfuscation", "java_obfuscation.jar");
    std::string vanillaDump = javapDumpJar("obfuscation", "build/vanilla.jar");
    ASSERT_FALSE(topoDump.empty()) << "javap on topo JAR produced no output";
    ASSERT_FALSE(vanillaDump.empty()) << "javap on vanilla JAR produced no output";

    // Structural assertions: check for method *declarations* (e.g.
    // `void internalHelper(int);`) via `\b<name>\(` regex, not plain
    // substring.  `Main.java` contains `assert ... : "internalHelper should
    // produce non-zero result"` as a human-readable error message —
    // ObfuscationPass intentionally leaves user string literals alone, so a
    // plain `find("internalHelper")` would always succeed regardless of
    // whether the method was renamed.  The leading word boundary plus
    // trailing `(` only matches a callsite or declaration, which is what we
    // actually care about.
    auto hasMethodDecl = [](const std::string& dump, const std::string& name) {
        std::regex pat("\\b" + name + "\\(");
        return std::regex_search(dump, pat);
    };

    // Vanilla must have the originals as methods — sanity check.
    EXPECT_TRUE(hasMethodDecl(vanillaDump, "internalHelper"))
        << "obfuscation: vanilla JAR missing `internalHelper(` — benchmark source changed?";
    EXPECT_TRUE(hasMethodDecl(vanillaDump, "secretCompute"))
        << "obfuscation: vanilla JAR missing `secretCompute(` — benchmark source changed?";

    // Topo JAR must have the obfuscated names and not the originals.
    // ObfuscationPass uses a `_t` prefix followed by 32 hex chars
    // (SipHash-2-4-128 → 16 bytes = 32 hex). The earlier
    // `_t[0-9a-f]{16}\b` regex was stale: it dated from the SHA-256/64
    // implementation that truncated to 16 hex. Under SipHash-128 the
    // 16-char prefix has another hex digit at position 18, so the
    // word-boundary `\b` never matched and the count was always 0 —
    // see issue jvm-obfuscation-pass-not-firing.
    std::regex obfPat(R"(_t[0-9a-f]{32}\b)");
    auto obfMatchBegin = std::sregex_iterator(topoDump.begin(), topoDump.end(), obfPat);
    auto obfMatchEnd = std::sregex_iterator();
    int obfCount = std::distance(obfMatchBegin, obfMatchEnd);
    EXPECT_GT(obfCount, 0) << "obfuscation: topo JAR contains no `_t<hex>` renamed methods — "
                           << "ObfuscationPass did not fire.";

    bool topoHasInternalHelper = hasMethodDecl(topoDump, "internalHelper");
    bool topoHasSecretCompute = hasMethodDecl(topoDump, "secretCompute");
    EXPECT_FALSE(topoHasInternalHelper)
        << "obfuscation: topo JAR still declares/calls `internalHelper(` — "
        << "pass did not rename private method.";
    EXPECT_FALSE(topoHasSecretCompute)
        << "obfuscation: topo JAR still declares/calls `secretCompute(` — "
        << "pass did not rename private method.";

    // Public method `run` and entrypoint `main` must be preserved.
    EXPECT_NE(topoDump.find(" run("), std::string::npos)
        << "obfuscation: topo JAR missing public `run()` — public method was renamed.";
    EXPECT_NE(topoDump.find(" main("), std::string::npos)
        << "obfuscation: topo JAR missing `main()` — entrypoint was renamed.";

    std::printf("[  INFO  ] jvm/obfuscation: %d _t<hex> symbols, originals present=(helper=%d,"
                " compute=%d)\n",
                obfCount, topoHasInternalHelper, topoHasSecretCompute);
}

} // namespace topo::test::e2e
