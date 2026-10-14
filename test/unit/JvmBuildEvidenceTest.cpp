#include "JavaDriver.h"
#include "topo/Build/ArtifactEvidence.h"
#include "topo/Distribution/Sha256.h"
#include "topo/Platform/Process.h"
#include "topo/Platform/ToolResolution.h"
#include <gtest/gtest.h>
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <set>

namespace fs = std::filesystem;
namespace ev = topo::build::evidence;
using json = nlohmann::json;
class JvmBuildEvidenceTest : public ::testing::Test {
protected:
    fs::path root;
    void SetUp() override {
        root = fs::temp_directory_path() / ("topo-jvm-evidence-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
        fs::create_directories(root); root = fs::canonical(root);
    }
    void TearDown() override { std::error_code ec; fs::remove_all(root, ec); }
    fs::path write(const std::string& name, const std::string& body) {
        auto path = root / name; fs::create_directories(path.parent_path()); std::ofstream(path) << body; return path;
    }
    json request() {
        write("Main.java", "public class Main { public static void main(String[] args) {} }\n");
        return {{"config", json::object()}, {"topoMetadata", json::object()}, {"visibilityEntries", json::array()},
            {"language", "java"}, {"sources", {(root / "Main.java").string()}}, {"outputPath", (root / "app.jar").string()},
            {"tempDir", (root / "build").string()}, {"keepTemps", true}, {"noIncremental", true},
            {"backendExtras", {{"targetVersion", "25"}}},
            {"evidence", {{"protocolVersion", 1}, {"mode", "commands"}, {"manifestPath", (root / "evidence.json").string()}}}};
    }
    topo::platform::CapturedProcessResult invoke(const json& req, bool model = false) {
        auto path = write("request.json", req.dump());
        return topo::platform::runProcessCapture(TOPO_BUILD_JVM_JAVA_EXE,
            model ? std::vector<std::string>{"--check-model", path.string(), "--output", (root / "model.json").string()}
                  : std::vector<std::string>{path.string()}, false);
    }
    json read(const fs::path& path) { std::ifstream in(path); return json::parse(in); }
    void bindModel(json& req) {
        req["checkModelPath"] = (root / "model.json").string();
        req["checkModelSha256"] = topo::dist::sha256File((root / "model.json").string());
    }
};

TEST_F(JvmBuildEvidenceTest, PureModelUsesActualSourceSelectionAndOrderedClasspath) {
    auto req = request();
    auto first = write("z-first.jar", "first"), second = write("a-second.jar", "second");
    write("empty-jdk/bin/javac", "unexecutable placeholder: model export must not run tools");
    write("empty-jdk/bin/javac.exe", "unexecutable placeholder: model export must not run tools");
    req["backendExtras"]["javaHome"] = (root / "empty-jdk").string();
    req["backendExtras"]["classpath"] = {first.string(), second.string()};
    auto cap = topo::platform::runProcessCapture(TOPO_BUILD_JVM_JAVA_EXE, {"--evidence-capabilities"}, false);
    ASSERT_EQ(cap.exitCode, 0); EXPECT_TRUE(json::parse(cap.stdoutOutput)["evidence"]["checkModel"].get<bool>());
    auto result = invoke(req, true); ASSERT_EQ(result.exitCode, 0) << result.stderrOutput;
    auto model = read(root / "model.json");
    EXPECT_EQ(model["files"], json::array({(root / "Main.java").string()}));
    auto cp = model["classpath"].get<std::vector<std::string>>();
    ASSERT_GE(cp.size(), 2u); EXPECT_EQ(cp[cp.size()-2], first.string()); EXPECT_EQ(cp.back(), second.string());
    EXPECT_FALSE(fs::exists(root / "build")); EXPECT_FALSE(fs::exists(root / "app.jar"));
}

TEST_F(JvmBuildEvidenceTest, ModelDigestAndSelectedFilesDriftFailBeforeCompilation) {
    auto req = request(); ASSERT_EQ(invoke(req, true).exitCode, 0); bindModel(req);
    std::ofstream(root / "model.json", std::ios::app) << " ";
    auto digest = invoke(req); EXPECT_NE(digest.exitCode, 0); EXPECT_NE(digest.stderrOutput.find("model digest"), std::string::npos);
    EXPECT_FALSE(fs::exists(root / "build"));
    ASSERT_EQ(invoke(req, true).exitCode, 0); bindModel(req);
    req["sources"].push_back(write("Hidden.java", "class Hidden {}\n").string());
    auto files = invoke(req); EXPECT_NE(files.exitCode, 0); EXPECT_NE(files.stderrOutput.find("actual compiler inputs"), std::string::npos);
    EXPECT_FALSE(fs::exists(root / "build"));
}

TEST_F(JvmBuildEvidenceTest, UnsupportedIsolationFailsBeforeCompilerExecution) {
    auto req = request(); req["evidence"]["mode"] = "isolated-build";
    auto result = invoke(req); EXPECT_NE(result.exitCode, 0);
    EXPECT_NE(result.stderrOutput.find("does not support"), std::string::npos) << result.stderrOutput;
    EXPECT_FALSE(fs::exists(root / "build")); EXPECT_FALSE(fs::exists(root / "app.jar"));
}

TEST_F(JvmBuildEvidenceTest, ModelBindsSamePathSourceClasspathBytesAndBackendRecipe) {
    auto req = request();
    auto dependency = write("dependency.jar", "original library bytes");
    req["backendExtras"]["classpath"] = {dependency.string()};
    ASSERT_EQ(invoke(req, true).exitCode, 0); bindModel(req);
    write("Main.java", "public class Main { public static void changed() {} }\n");
    auto source = invoke(req); EXPECT_NE(source.exitCode, 0);
    EXPECT_NE(source.stderrOutput.find("actual compiler inputs"), std::string::npos);
    EXPECT_FALSE(fs::exists(root / "build"));
    ASSERT_EQ(invoke(req, true).exitCode, 0); bindModel(req);
    write("dependency.jar", "replacement library bytes");
    auto classpath = invoke(req); EXPECT_NE(classpath.exitCode, 0);
    EXPECT_NE(classpath.stderrOutput.find("actual compiler inputs"), std::string::npos);
    EXPECT_FALSE(fs::exists(root / "build"));
    ASSERT_EQ(invoke(req, true).exitCode, 0); bindModel(req);
    req["backendExtras"]["jvmArgs"] = {"-Dunreviewed=true"};
    auto recipe = invoke(req); EXPECT_NE(recipe.exitCode, 0);
    EXPECT_NE(recipe.stderrOutput.find("actual compiler inputs"), std::string::npos);
    EXPECT_FALSE(fs::exists(root / "build"));
}

TEST_F(JvmBuildEvidenceTest, ActualCompilerTransformBundleAndJarHaveVerifiableParentChain) {
    auto tools = fs::canonical(TOPO_BUILD_JVM_JAVA_EXE).parent_path();
    if (!fs::is_regular_file(tools / "topo-transform.jar") || !fs::is_regular_file(tools / "topo-runtime.jar"))
        GTEST_SKIP() << "Build transformer and install Java runtime to exercise actual JVM stage evidence";
    auto javac = topo::platform::findSpawnableOnPath(topo::jvm::resolveJavaTool("javac", ""));
    ASSERT_FALSE(javac.empty()) << "A real JDK 25 is required";
    auto jdk = fs::canonical(javac).parent_path().parent_path();
    auto req = request(); req["backendExtras"]["javaHome"] = jdk.string();
    auto sentinel = (root / "static-initializer-ran").generic_string();
    write("Main.java", "public class Main { static { try { java.nio.file.Files.writeString(java.nio.file.Path.of(\"" + sentinel + "\"), \"ran\"); } catch (Exception e) { throw new RuntimeException(e); } } public static void main(String[] args) {} }\n");
    ASSERT_EQ(invoke(req, true).exitCode, 0); bindModel(req);
    auto result = invoke(req); ASSERT_EQ(result.exitCode, 0) << result.stderrOutput << result.stdoutOutput;
    EXPECT_TRUE(fs::is_regular_file(root / "app.jar")); EXPECT_FALSE(fs::exists(root / "static-initializer-ran"));
    ev::Manifest manifest; std::string error;
    ASSERT_TRUE(ev::readManifest((root / "evidence.json").string(), manifest, error)) << error;
    ev::RootMap roots = {{"project", fs::canonical(fs::current_path())}, {"temp", root / "build"},
        {"artifact", root}, {"tools", tools}, {"request", root}, {"jdk", jdk}, {"source-0", root},
        {"dependency-0", tools}, {"extra-2", root}};
    auto verified = ev::verifyManifest(manifest, roots);
    EXPECT_EQ(verified.diagnostics, std::vector<std::string>{"evidence declares incomplete coverage"});
    EXPECT_TRUE(verified.integrity); EXPECT_FALSE(verified.buildEvidence);
    std::set<std::string> kinds;
    for (const auto& node : manifest.nodes) {
        kinds.insert(node.action.kind); EXPECT_FALSE(node.complete);
        EXPECT_EQ(node.producer.at("exitCode"), "0");
        if (node.producer.count("captureDiagnostics")) EXPECT_EQ(node.producer.at("captureDiagnostics"), "[]");
        EXPECT_EQ(node.parents.size(), node.action.kind == "javac" ? 0u : 1u);
        bool requestCaptured = false, modelCaptured = false;
        for (const auto& input : node.action.inputs) {
            requestCaptured |= fs::path(input.path).filename() == "request.json";
            modelCaptured |= fs::path(input.path).filename() == "model.json";
        }
        EXPECT_TRUE(requestCaptured) << node.action.kind; EXPECT_TRUE(modelCaptured) << node.action.kind;
    }
    EXPECT_EQ(kinds, (std::set<std::string>{"javac", "jvm-verify", "jvm-transform", "jvm-runtime-bundle",
        "jvm-post-verify", "javap-main-discovery", "jar-package"}));
    auto package = std::find_if(manifest.nodes.begin(), manifest.nodes.end(), [&](const auto& node){ return node.id == manifest.roots.at(0); });
    ASSERT_NE(package, manifest.nodes.end()); EXPECT_EQ(package->action.kind, "jar-package");
    std::ofstream(root / "app.jar", std::ios::app) << "tampered";
    EXPECT_FALSE(ev::verifyManifest(manifest, roots).integrity);
}

TEST_F(JvmBuildEvidenceTest, RepeatedBuildDropsClassesRemovedFromTheSelectedSource) {
    auto tools = fs::canonical(TOPO_BUILD_JVM_JAVA_EXE).parent_path();
    if (!fs::is_regular_file(tools / "topo-transform.jar") || !fs::is_regular_file(tools / "topo-runtime.jar"))
        GTEST_SKIP() << "Build transformer and install Java runtime to exercise actual repeated builds";
    auto javac = topo::platform::findSpawnableOnPath(topo::jvm::resolveJavaTool("javac", ""));
    ASSERT_FALSE(javac.empty()) << "A real JDK 25 is required";
    auto jdk = fs::canonical(javac).parent_path().parent_path();
    auto req = request(); req["backendExtras"]["javaHome"] = jdk.string();
    req["backendExtras"]["mainClass"] = "Main";
    for (int iteration = 0; iteration < 2; ++iteration) {
        write("Main.java", iteration == 0
            ? "public class Main {static class Removed {} public static void main(String[] args){}}\n"
            : "public class Main {public static void main(String[] args){}}\n");
        ASSERT_EQ(invoke(req, true).exitCode, 0); bindModel(req);
        auto build = invoke(req); ASSERT_EQ(build.exitCode, 0) << build.stderrOutput;
        auto listing = topo::platform::runProcessCapture(topo::jvm::resolveJavaTool("jar", jdk.string()),
            {"tf", (root / "app.jar").string()}, false);
        ASSERT_EQ(listing.exitCode, 0);
        EXPECT_NE(listing.stdoutOutput.find("Main.class"), std::string::npos);
        EXPECT_EQ(listing.stdoutOutput.find("Main$Removed.class") != std::string::npos, iteration == 0);
    }
}
