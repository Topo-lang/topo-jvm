#include "E2eHarness.h"

#include "topo/Platform/Process.h"

#include <cstring>
#include <fstream>

namespace topo::test::e2e {

class JavaFunctional : public E2eFixture {
protected:
    RunResult topoBuildJvm(const std::string& projectName) {
        fs::path projDir = jvmBenchmarksDir_ / projectName;
        std::string exe = topoBuildExe_.generic_string();
        std::string workDir = projDir.generic_string();
        auto r = platform::runProcessCapture(exe, {}, workDir);
        return RunResult{r.exitCode, mergeOutput(r)};
    }

    RunResult runJvmJar(const std::string& projectName, const std::string& jarName) {
        fs::path jarPath = jvmBenchmarksDir_ / projectName / "build" / jarName;
        if (!fs::exists(jarPath)) {
            // Also check project root
            jarPath = jvmBenchmarksDir_ / projectName / jarName;
        }
        return runJar(jarPath.generic_string());
    }
};

// --- Java Visibility ---
TEST_F(JavaFunctional, JavaVisibility) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    ASSERT_TRUE(fs::exists(jvmBenchmarksDir_ / "visibility")) << "visibility benchmark not found";

    auto build = topoBuildJvm("visibility");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    auto run = runJvmJar("visibility", "java_visibility.jar");
    ASSERT_EQ(run.exitCode, 0) << "JAR execution failed:\n" << run.output;

    assertOutputMatches(run.output,
                        "...\n"
                        "java_visibility: all assertions passed\n");
}

// --- Java VisibilityPass sidecar ---
//
// After `topo-build` runs the JVM transform, it should emit
// `<jar>.topo-passes/VisibilityPass.json` with a protocol-conformant
// header + a `rewrites[]` array listing each (class, method) whose access
// flag changed to match the .topo declaration. The visibility benchmark
// project declares public/protected/private methods on `app::Main`, so we
// expect non-zero rewrites and the file's existence to demonstrate the
// cross-backend sidecar protocol is now wired through JVM.
TEST_F(JavaFunctional, JavaVisibilityWritesSidecar) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    fs::path projDir = jvmBenchmarksDir_ / "visibility";
    ASSERT_TRUE(fs::exists(projDir)) << "visibility benchmark not found";

    // Compute sidecar location relative to the build output. The Topo.toml
    // sets `output = "java_visibility.jar"` and `topo build` typically lands
    // outputs in `<project>/build/<output>`.
    fs::path expected1 = projDir / "build" / "java_visibility.jar.topo-passes" / "VisibilityPass.json";
    fs::path expected2 = projDir / "java_visibility.jar.topo-passes" / "VisibilityPass.json";

    // Clean any prior sidecar so this test reflects the current build.
    std::error_code ec;
    fs::remove_all(expected1.parent_path(), ec);
    fs::remove_all(expected2.parent_path(), ec);

    auto build = topoBuildJvm("visibility");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    fs::path sidecar = fs::exists(expected1) ? expected1 : expected2;
    ASSERT_TRUE(fs::exists(sidecar))
        << "Sidecar not written. Looked at:\n  " << expected1
        << "\n  " << expected2 << "\nbuild output:\n" << build.output;

    std::ifstream in(sidecar);
    std::string body((std::istreambuf_iterator<char>(in)),
                     std::istreambuf_iterator<char>());
    ASSERT_FALSE(body.empty()) << "Sidecar is empty: " << sidecar;

    // Common sidecar header — 7 fields.
    EXPECT_NE(body.find("\"pass\": \"VisibilityPass\""), std::string::npos)
        << "Missing pass field:\n" << body;
    EXPECT_NE(body.find("\"category\":"), std::string::npos)
        << "Missing category field:\n" << body;
    EXPECT_NE(body.find("\"fired\": true"), std::string::npos)
        << "Sidecar should report fired=true (benchmark declares non-public methods):\n"
        << body;
    EXPECT_NE(body.find("\"fired_count\":"), std::string::npos)
        << "Missing fired_count field:\n" << body;
    EXPECT_NE(body.find("\"decision\":"), std::string::npos)
        << "Missing decision field:\n" << body;
    EXPECT_NE(body.find("\"reason\":"), std::string::npos)
        << "Missing reason field:\n" << body;
    EXPECT_NE(body.find("\"elapsed_ns\":"), std::string::npos)
        << "Missing elapsed_ns field:\n" << body;

    // Rewrites — must include at least one Main method and reference one
    // of the declared visibilities (private / protected).
    EXPECT_NE(body.find("\"rewrites\":"), std::string::npos)
        << "Missing rewrites field:\n" << body;
    EXPECT_NE(body.find("Main"), std::string::npos)
        << "Rewrites should mention class Main:\n" << body;
}

// --- InlineHintPass + StaticPromotionPass sidecars ---
//
// The visibility benchmark exercises ACC_FINAL injection on internal /
// private methods (InlineHintPass runs at opt_level >= 1 which is the
// dev-mode default). The visibility benchmark has no `isStatic` methods,
// so StaticPromotionPass writes a `fired=false` sidecar — proving that
// "Pass ran but had nothing to do" is distinguishable from "Pass
// disabled" (in which case the sidecar would be absent). Together with
// the existing VisibilityPass sidecar, this exercises 3 of the
// remaining ✓-debug JVM Pass sidecars.
TEST_F(JavaFunctional, JavaInlineHintAndStaticPromotionSidecars) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";
    fs::path projDir = jvmBenchmarksDir_ / "visibility";
    fs::path passesDir1 = projDir / "build" / "java_visibility.jar.topo-passes";
    fs::path passesDir2 = projDir / "java_visibility.jar.topo-passes";

    std::error_code ec;
    fs::remove_all(passesDir1, ec);
    fs::remove_all(passesDir2, ec);

    auto build = topoBuildJvm("visibility");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    fs::path passesDir = fs::exists(passesDir1) ? passesDir1 : passesDir2;

    // InlineHintPass — fires on internal/private methods (helperCompute /
    // resetState in this benchmark).
    fs::path inlinePath = passesDir / "InlineHintPass.json";
    ASSERT_TRUE(fs::exists(inlinePath))
        << "InlineHintPass.json missing at: " << inlinePath;
    std::ifstream inlineIn(inlinePath);
    std::string inlineBody((std::istreambuf_iterator<char>(inlineIn)),
                           std::istreambuf_iterator<char>());
    EXPECT_NE(inlineBody.find("\"pass\": \"InlineHintPass\""), std::string::npos)
        << "Wrong/missing pass field:\n" << inlineBody;
    EXPECT_NE(inlineBody.find("\"category\": \"INFRA\""), std::string::npos)
        << "Missing category=INFRA:\n" << inlineBody;
    EXPECT_NE(inlineBody.find("\"fired\": true"), std::string::npos)
        << "Should fire on internal/private methods:\n" << inlineBody;
    EXPECT_NE(inlineBody.find("\"final_methods\":"), std::string::npos)
        << "Missing final_methods field:\n" << inlineBody;
    EXPECT_NE(inlineBody.find("\"final_classes\":"), std::string::npos)
        << "Missing final_classes field:\n" << inlineBody;
    EXPECT_NE(inlineBody.find("Main"), std::string::npos)
        << "final_methods should reference class Main:\n" << inlineBody;

    // StaticPromotionPass — present-but-not-fired (no isStatic methods).
    fs::path spPath = passesDir / "StaticPromotionPass.json";
    ASSERT_TRUE(fs::exists(spPath))
        << "StaticPromotionPass.json missing at: " << spPath;
    std::ifstream spIn(spPath);
    std::string spBody((std::istreambuf_iterator<char>(spIn)),
                       std::istreambuf_iterator<char>());
    EXPECT_NE(spBody.find("\"pass\": \"StaticPromotionPass\""), std::string::npos)
        << "Wrong/missing pass field:\n" << spBody;
    EXPECT_NE(spBody.find("\"fired\": false"), std::string::npos)
        << "Visibility benchmark has no isStatic; expected fired=false:\n" << spBody;
    EXPECT_NE(spBody.find("\"decision\": \"no_promotions\""), std::string::npos)
        << "Wrong decision string when nothing promoted:\n" << spBody;
    EXPECT_NE(spBody.find("\"promoted_methods\":"), std::string::npos)
        << "Missing promoted_methods field:\n" << spBody;
}

// --- Java Stages ---
TEST_F(JavaFunctional, JavaStages) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto build = topoBuildJvm("stages");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    auto run = runJvmJar("stages", "java_stages.jar");
    ASSERT_EQ(run.exitCode, 0) << "JAR execution failed:\n" << run.output;

    assertOutputMatches(run.output,
                        "...\n"
                        "java_stages: all assertions passed\n");
}

// --- Java Pipeline ---
TEST_F(JavaFunctional, JavaPipeline) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto build = topoBuildJvm("pipeline");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    auto run = runJvmJar("pipeline", "java_pipeline.jar");
    ASSERT_EQ(run.exitCode, 0) << "JAR execution failed:\n" << run.output;

    assertOutputMatches(run.output,
                        "...\n"
                        "java_pipeline: all assertions passed\n");
}

// --- Java Parallel ---
TEST_F(JavaFunctional, JavaParallel) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto build = topoBuildJvm("parallel");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    auto run = runJvmJar("parallel", "java_parallel.jar");
    ASSERT_EQ(run.exitCode, 0) << "JAR execution failed:\n" << run.output;

    assertOutputMatches(run.output,
                        "...\n"
                        "java_parallel: all assertions passed\n");
}

// --- Java Build Error ---
TEST_F(JavaFunctional, JavaBuildError) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    // Create a temporary project with invalid Java source
    fs::path tempDir = fs::temp_directory_path() / "topo-e2e-java-error";
    fs::create_directories(tempDir / "src" / "main" / "java");
    fs::create_directories(tempDir / "topo");

    // Write invalid Java source
    {
        std::ofstream f(tempDir / "src" / "main" / "java" / "Bad.java");
        f << "package app;\npublic class Bad { this is not valid java }\n";
    }

    // Write Topo.toml
    {
        std::ofstream f(tempDir / "Topo.toml");
        f << "[project]\nname = \"bad_java\"\n\n"
          << "[topo]\nroot = \"topo/main.topo\"\n\n"
          << "[build]\nlanguage = \"java\"\n"
          << "sources = [\"src/main/java\"]\n"
          << "output = \"bad.jar\"\noutput_type = \"exe\"\n\n"
          << "[build.java]\ntarget_version = \"21\"\n\n"
          << "[builder]\nmode = \"dev\"\n";
    }

    // Write minimal .topo file
    {
        std::ofstream f(tempDir / "topo" / "main.topo");
        f << "namespace app {\n  public:\n    class Bad {}\n}\n";
    }

    // Build should fail
    std::string exe = topoBuildExe_.generic_string();
    auto r = platform::runProcessCapture(exe, {}, tempDir.generic_string());
    EXPECT_NE(r.exitCode, 0) << "Build should have failed for invalid Java source";
    EXPECT_FALSE(r.stderrOutput.empty()) << "Should produce error output on stderr";

    // Cleanup
    std::error_code ec;
    fs::remove_all(tempDir, ec);
}

// --- Java Observability ---
TEST_F(JavaFunctional, JavaObservability) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto build = topoBuildJvm("observability");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    auto run = runJvmJar("observability", "java_observability.jar");
    ASSERT_EQ(run.exitCode, 0) << "JAR execution failed:\n" << run.output;

    assertOutputMatches(run.output,
                        "...\n"
                        "java_observability: all assertions passed\n");
}

// --- Java Adaptive ---
TEST_F(JavaFunctional, JavaAdaptive) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto build = topoBuildJvm("adaptive");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    auto run = runJvmJar("adaptive", "java_adaptive.jar");
    ASSERT_EQ(run.exitCode, 0) << "JAR execution failed:\n" << run.output;

    assertOutputMatches(run.output,
                        "...\n"
                        "java_adaptive: all assertions passed\n");
}

// --- Java Obfuscation ---
TEST_F(JavaFunctional, JavaObfuscation) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto build = topoBuildJvm("obfuscation");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    auto run = runJvmJar("obfuscation", "java_obfuscation.jar");
    ASSERT_EQ(run.exitCode, 0) << "JAR execution failed:\n" << run.output;

    assertOutputMatches(run.output,
                        "...\n"
                        "java_obfuscation: all assertions passed\n");
}

// --- Java DataLayout ---
TEST_F(JavaFunctional, JavaDataLayout) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto build = topoBuildJvm("data_layout");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    auto run = runJvmJar("data_layout", "java_data_layout.jar");
    ASSERT_EQ(run.exitCode, 0) << "JAR execution failed:\n" << run.output;

    assertOutputMatches(run.output,
                        "...\n"
                        "java_data_layout: all assertions passed\n");
}

// --- Java LoopVectorize ---
TEST_F(JavaFunctional, JavaLoopVectorize) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto build = topoBuildJvm("loop_vectorize");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    // LoopVectorizePass generates Vector API code requiring --add-modules
    fs::path jarPath = jvmBenchmarksDir_ / "loop_vectorize" / "java_loop_vectorize.jar";
    std::string javaExe = "java";
    const char* javaHome = std::getenv("JAVA_HOME");
    if (javaHome && std::strlen(javaHome) > 0) {
        fs::path candidate = fs::path(javaHome) / "bin" / "java";
        if (fs::exists(candidate)) javaExe = candidate.generic_string();
    }
    auto r = platform::runProcessCapture(
        javaExe, {"-ea", "--add-modules", "jdk.incubator.vector", "-jar", jarPath.generic_string()});
    ASSERT_EQ(r.exitCode, 0) << "JAR execution failed:\n" << r.stdoutOutput;

    assertOutputMatches(r.stdoutOutput,
                        "...\n"
                        "java_loop_vectorize: all assertions passed\n");
}

// --- Java Lifetime ---
TEST_F(JavaFunctional, JavaLifetime) {
    ASSERT_FALSE(jvmBenchmarksDir_.empty()) << "TOPO_JVM_BENCHMARKS_DIR not set";

    auto build = topoBuildJvm("lifetime");
    ASSERT_EQ(build.exitCode, 0) << "topo-build failed:\n" << build.output;

    auto run = runJvmJar("lifetime", "java_lifetime.jar");
    ASSERT_EQ(run.exitCode, 0) << "JAR execution failed:\n" << run.output;

    assertOutputMatches(run.output,
                        "...\n"
                        "java_lifetime: all assertions passed\n");
}

} // namespace topo::test::e2e
