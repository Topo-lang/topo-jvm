// topo-build-jvm-java per-value backendExtras validation tests.
//
// Spawns the actual topo-build-jvm-java binary with hand-crafted
// BackendRequest JSON to assert that the three constrained JVM
// backendExtras values (javaHome / targetVersion / jvmArgs) are
// rejected before the build steps run.
//
// Diagnostic label shape pinned: `error: <label>: '<offending value>'`.

#include "topo/Platform/Process.h"

#include <gtest/gtest.h>
#include <nlohmann/json.hpp>

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>

#ifdef _WIN32
#include <process.h>
#else
#include <unistd.h>
#endif

namespace fs = std::filesystem;
using json = nlohmann::json;

namespace {

#ifdef _WIN32
int testPid() { return _getpid(); }
#else
int testPid() { return getpid(); }
#endif

class JvmBackendExtrasInputTrust : public ::testing::Test {
protected:
    fs::path testDir;

    void SetUp() override {
        testDir = fs::temp_directory_path() /
                  ("topo-jvm-extras-trust_" + std::to_string(testPid()) + "_" +
                   std::to_string(reinterpret_cast<std::uintptr_t>(this)));
        fs::create_directories(testDir);
    }

    void TearDown() override {
        std::error_code ec;
        fs::remove_all(testDir, ec);
    }

    /// Build a minimal-but-deserializable BackendRequest with the given
    /// JVM-language backendExtras payload.
    json makeRequest(const json& backendExtras) const {
        json j = json::object();
        j["outputPath"] = (testDir / "out.jar").string();
        j["tempDir"] = (testDir / "tmp").string();
        j["language"] = "java";
        j["config"] = json::object();
        j["topoMetadata"] = json::object();
        j["visibilityEntries"] = json::array();
        j["backendExtras"] = backendExtras;
        return j;
    }

    /// Write `req` to `<testDir>/request.json` and invoke
    /// `topo-build-jvm-java <request.json>`, capturing stderr.
    topo::platform::CapturedProcessResult invoke(const json& req) const {
        fs::path reqPath = testDir / "request.json";
        std::ofstream(reqPath) << req.dump();
        return topo::platform::runProcessCapture(
            TOPO_BUILD_JVM_JAVA_EXE, {reqPath.string()}, false);
    }
};

} // namespace

// --- javaHome ----------------------------------------------------

TEST_F(JvmBackendExtrasInputTrust, JvmJavaHomeMustExist) {
    json extras = json::object();
    extras["javaHome"] = "/no/such/directory/anywhere/please";
    auto result = invoke(makeRequest(extras));

    EXPECT_NE(result.exitCode, 0);
    EXPECT_NE(result.stderrOutput.find("javaHome"), std::string::npos)
        << "expected diagnostic mentioning 'javaHome'; stderr was:\n"
        << result.stderrOutput;
}

// --- targetVersion -----------------------------------------------

TEST_F(JvmBackendExtrasInputTrust, JvmTargetVersionMustBeRecognised) {
    json extras = json::object();
    extras["targetVersion"] = "banana";
    auto result = invoke(makeRequest(extras));

    EXPECT_NE(result.exitCode, 0);
    EXPECT_NE(result.stderrOutput.find("targetVersion"), std::string::npos)
        << "expected diagnostic mentioning 'targetVersion'; stderr was:\n"
        << result.stderrOutput;
    EXPECT_NE(result.stderrOutput.find("banana"), std::string::npos)
        << "expected diagnostic to echo offending value 'banana'; stderr was:\n"
        << result.stderrOutput;
}

TEST_F(JvmBackendExtrasInputTrust, JvmTargetVersionRecognisedSetAccepted) {
    // Positive control: each of the four recognised versions passes the
    // targetVersion gate. We can't actually finish a build (no .java
    // sources, no JDK staged for unit tests), but we can assert the
    // rejection message does NOT mention targetVersion.
    for (const std::string& v : {"8", "11", "17", "21"}) {
        json extras = json::object();
        extras["targetVersion"] = v;
        auto result = invoke(makeRequest(extras));
        EXPECT_EQ(result.stderrOutput.find("targetVersion:"), std::string::npos)
            << "unexpected targetVersion rejection for '" << v
            << "'; stderr was:\n" << result.stderrOutput;
    }
}

// --- jvmArgs -----------------------------------------------------

TEST_F(JvmBackendExtrasInputTrust, JvmJvmArgsBootclasspathPrefixRejected) {
    json extras = json::object();
    extras["jvmArgs"] = std::vector<std::string>{"-Xbootclasspath/p:/tmp/x"};
    auto result = invoke(makeRequest(extras));

    EXPECT_NE(result.exitCode, 0);
    EXPECT_NE(result.stderrOutput.find("jvmArgs"), std::string::npos)
        << "expected diagnostic mentioning 'jvmArgs'; stderr was:\n"
        << result.stderrOutput;
    EXPECT_NE(result.stderrOutput.find("-Xbootclasspath/p:"),
              std::string::npos)
        << "expected diagnostic to mention forbidden prefix; stderr was:\n"
        << result.stderrOutput;
}

TEST_F(JvmBackendExtrasInputTrust, JvmJvmArgsBootclasspathAPrefixRejected) {
    json extras = json::object();
    extras["jvmArgs"] = std::vector<std::string>{"-Xbootclasspath/a:/tmp/x"};
    auto result = invoke(makeRequest(extras));

    EXPECT_NE(result.exitCode, 0);
    EXPECT_NE(result.stderrOutput.find("jvmArgs"), std::string::npos)
        << "expected diagnostic mentioning 'jvmArgs'; stderr was:\n"
        << result.stderrOutput;
}

// --- unknown key (deserializer rejection) ------------------------

TEST_F(JvmBackendExtrasInputTrust, JvmUnknownKeyRejectedAtDeserialize) {
    json extras = json::object();
    extras["mysteryKey"] = "anything";
    auto result = invoke(makeRequest(extras));

    EXPECT_NE(result.exitCode, 0);
    EXPECT_NE(result.stderrOutput.find("mysteryKey"), std::string::npos)
        << "expected diagnostic mentioning 'mysteryKey'; stderr was:\n"
        << result.stderrOutput;
}
