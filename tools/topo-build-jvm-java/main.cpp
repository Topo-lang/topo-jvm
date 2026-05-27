// topo-build-jvm-java — JVM backend for Java projects
//
// Reads a JSON request file (argv[1]) from topo-build, executes:
//   1. Compile .java sources to .class files (javac)
//   2. Verify Topo/bytecode consistency (topo-transform.jar --verify)
//   3. Apply bytecode transforms (topo-transform.jar --transform)
//   3b. Bundle SDK runtime classes into transformed output
//   3c. Post-transform consistency re-check
//   4. Package .class files into output JAR

#include "JavaDriver.h"

#include "topo/Build/BackendProtocol.h"
#include "topo/Build/PassConfig.h"
#include "topo/Build/SymbolTableJson.h"
#include "topo/Platform/Platform.h"
#include "topo/Platform/Process.h"
#include "topo/Platform/SharedLibrary.h"

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>

namespace fs = std::filesystem;

/// Write a JSON value to a file. Returns true on success.
static bool writeJsonFile(const fs::path& path, const nlohmann::json& j) {
    std::ofstream ofs(path);
    if (!ofs) {
        std::cerr << "error: cannot write '" << path.string() << "'\n";
        return false;
    }
    ofs << j.dump(2);
    return ofs.good();
}

/// Join strings with the platform classpath separator.
static std::string joinClasspath(const std::vector<std::string>& parts) {
    if (parts.empty()) return {};
    char sep = topo::platform::IsWindows ? ';' : ':';
    std::string result = parts[0];
    for (size_t i = 1; i < parts.size(); ++i) {
        result += sep;
        result += parts[i];
    }
    return result;
}

// ============================================================
// backendExtras per-value validators
//
// The schema for these keys is defined in the topo-jvm
// backend-extras spec and cross-referenced by the topo-build
// backend-protocol Failure-modes table. Diagnostic shape matches
// existing `error: <label>: '<value>'` messages elsewhere in this
// tool.
// ============================================================

/// Validate `javaHome`. Empty is allowed (caller falls back to JAVA_HOME
/// then PATH). Non-empty must be an existing directory containing
/// `bin/javac` (or `bin\javac.exe` on Windows).
static bool validateJavaHome(const std::string& javaHome) {
    if (javaHome.empty()) return true; // fall-through to env/PATH
    std::error_code ec;
    fs::path root(javaHome);
    if (!fs::exists(root, ec) || !fs::is_directory(root, ec)) {
        std::cerr << "error: javaHome: directory does not exist: '"
                  << javaHome << "'\n";
        return false;
    }
    fs::path javac = root / "bin" /
                     (std::string("javac") +
                      (topo::platform::IsWindows ? ".exe" : ""));
    if (!fs::exists(javac, ec)) {
        std::cerr << "error: javaHome: 'bin/javac"
                  << (topo::platform::IsWindows ? ".exe" : "")
                  << "' not found under: '" << javaHome << "'\n";
        return false;
    }
    return true;
}

/// Validate `targetVersion`. Must be one of the recognised JDK versions
/// the schema lists: "8" / "11" / "17" / "21".
static bool validateTargetVersion(const std::string& targetVersion) {
    static const std::vector<std::string> recognised = {"8", "11", "17", "21"};
    for (const auto& v : recognised) {
        if (targetVersion == v) return true;
    }
    std::cerr << "error: targetVersion: '" << targetVersion
              << "' not in recognised set {8, 11, 17, 21}\n";
    return false;
}

/// Validate every `jvmArgs` entry. Reject prefix-attack style entries
/// (notably `-Xbootclasspath/p:` and friends) that could replace
/// standard-library classes loaded by topo-transform.jar.
static bool validateJvmArgs(const std::vector<std::string>& jvmArgs) {
    static const std::vector<std::string> forbiddenPrefixes = {
        "-Xbootclasspath/p:",
        "-Xbootclasspath/a:",
        "-Xbootclasspath:",
    };
    for (const auto& arg : jvmArgs) {
        for (const auto& bad : forbiddenPrefixes) {
            if (arg.rfind(bad, 0) == 0) {
                std::cerr << "error: jvmArgs: forbidden prefix '" << bad
                          << "' in entry: '" << arg << "'\n";
                return false;
            }
        }
    }
    return true;
}

int main(int argc, char* argv[]) {
    if (argc < 2) {
        std::cerr << "Usage: " << argv[0] << " <request.json> [--keep-metadata]\n"
                  << "  Backend tool invoked by topo-build. Not intended for direct use.\n";
        return 1;
    }

    // Parse arguments
    std::string requestPath = argv[1];
    bool keepMetadata = false;
    for (int i = 2; i < argc; ++i) {
        if (std::string(argv[i]) == "--keep-metadata") keepMetadata = true;
    }
    std::ifstream ifs(requestPath);
    if (!ifs) {
        std::cerr << "error: cannot open request file '" << requestPath << "'\n";
        return 1;
    }

    std::ostringstream buf;
    buf << ifs.rdbuf();
    std::string jsonStr = buf.str();

    topo::build::BackendRequest req;
    if (!topo::build::deserializeBackendRequest(jsonStr, req)) {
        std::cerr << "error: failed to parse backend request JSON\n";
        return 1;
    }

    // Extract JVM-specific fields from backendExtras
    std::string javaHome = req.backendExtras.value("javaHome", std::string());
    // Pin to Java 21 by default. The ceiling is set by two independent
    // constraints:
    //   * topo-jvm/decompile/build.gradle.kts still pins ASM 9.7.1, which
    //     reads up to class-file major v65 (Java 21). decompile/ feeds
    //     JVMLifter; raising its ASM version requires a corresponding
    //     bump there too.
    //   * topo-jvm/transform/build.gradle.kts pins the transform JVM
    //     toolchain to JDK 21 (`languageVersion.set(of(21))`), so the
    //     emitted topo-transform.jar is itself v65 bytecode regardless
    //     of what its bundled ASM 9.8 could parse.
    // Anything newer (JDK 22+ emits v66+) breaks the verify/transform step
    // with `Unsupported class file major version`. JDK 17 cannot `--release 21`
    // — the JAVA_HOME pointed at by `topo-build` must be ≥21. Callers can
    // override via `backendExtras.targetVersion`; topo-build forwards
    // `[build.java].target_version` from Topo.toml into that field.
    std::string targetVersion = req.backendExtras.value("targetVersion", std::string("21"));
    std::vector<std::string> jvmArgs;
    if (req.backendExtras.contains("jvmArgs")) jvmArgs = req.backendExtras["jvmArgs"].get<std::vector<std::string>>();

    // Per-value validation of JVM backendExtras inputs. Centralised
    // unknown-key check already ran in deserializeBackendRequest; this
    // tightens the *values* of the keys the spec constrains. Validate
    // jvmArgs BEFORE backend-injected entries so we only ever check
    // user-supplied values.
    if (!validateJavaHome(javaHome)) return 1;
    if (!validateTargetVersion(targetVersion)) return 1;
    if (!validateJvmArgs(jvmArgs)) return 1;

    // Inject --add-modules for Vector API when loop vectorization is enabled
    if (req.config.loopParallelCfg.mode != topo::FeatureMode::Off) {
        jvmArgs.push_back("--add-modules");
        jvmArgs.push_back("jdk.incubator.vector");
    }
    std::string userClasspath;
    if (req.backendExtras.contains("classpath")) {
        auto cp = req.backendExtras["classpath"].get<std::vector<std::string>>();
        userClasspath = joinClasspath(cp);
    }

    fs::path tempDir(req.tempDir);
    std::error_code ec;
    fs::create_directories(tempDir, ec);
    if (ec) {
        std::cerr << "error: cannot create temp directory '" << tempDir.string()
                  << "': " << ec.message() << "\n";
        return 1;
    }

    // Locate topo-transform.jar relative to this executable
    std::string exeDir = topo::platform::getExecutableDir();
    fs::path transformJar = fs::path(exeDir) / "topo-transform.jar";
    if (!fs::exists(transformJar)) {
        std::cerr << "error: topo-transform.jar not found at '" << transformJar.string() << "'\n";
        return 1;
    }

    // Write metadata and config JSON files for the transformer
    fs::path metadataPath = tempDir / "topo-metadata.json";
    {
        nlohmann::json metaJson = topo::serializeSymbolTable(req.symbolTable);
        if (!writeJsonFile(metadataPath, metaJson)) return 1;
    }

    fs::path configPath = tempDir / "backend-config.json";
    {
        nlohmann::json configJson;
        configJson["optLevel"] = static_cast<int>(req.config.optLevel);
        configJson["buildMode"] = static_cast<int>(req.config.buildMode);
        configJson["noVerify"] = req.config.noVerify;
        configJson["warnOnly"] = req.config.warnOnly;
        configJson["dumpMap"] = req.config.dumpMap;
        configJson["dumpIR"] = req.config.dumpIR;

        // Feature configs — PassPipeline checks these to enable/disable passes
        auto serializeFeature = [](topo::FeatureMode mode) -> nlohmann::json {
            nlohmann::json j;
            j["mode"] = topo::featureModeToString(mode);
            return j;
        };
        configJson["parallelCfg"] = serializeFeature(req.config.parallelCfg.mode);
        configJson["adaptiveCfg"] = serializeFeature(req.config.adaptiveCfg.mode);
        configJson["observabilityCfg"] = serializeFeature(req.config.observabilityCfg.mode);
        configJson["dataLayoutCfg"] = serializeFeature(req.config.dataLayoutCfg.mode);
        configJson["loopParallelCfg"] = serializeFeature(req.config.loopParallelCfg.mode);
        configJson["lifetimeCfg"] = serializeFeature(req.config.lifetimeCfg.mode);
        configJson["lifetimeCfg"]["defaultArenaSize"] = req.config.lifetimeCfg.defaultArenaSize;
        configJson["prefetchCfg"] = serializeFeature(req.config.prefetchCfg.mode);
        configJson["prefetchCfg"]["distance"] = req.config.prefetchCfg.distance;
        configJson["typeNarrowingCfg"] = serializeFeature(req.config.typeNarrowingCfg.mode);
        // pipelineCfg gates PassPipeline.java::isPipelineEnabled().  Default
        // on the C++ side is Auto (pass runs whenever a pipeline logic block
        // exists); Topo-base.toml benchmarks override to `off` to get a
        // genuine "no pipeline rewrite" baseline.
        configJson["pipelineCfg"] = serializeFeature(req.config.pipelineCfg.mode);

        configJson["obfMode"] = req.config.obfMode == topo::ObfuscationMode::Salted ? "salted" : "normal";
        configJson["obfSalt"] = req.config.obfSalt;

        if (!writeJsonFile(configPath, configJson)) return 1;
    }

    // ================================================================
    // Step 1: Compile .java sources to .class files
    // ================================================================
    std::cerr << "[1/4] Compiling Java sources...\n";

    // Add SDK runtime jar to classpath so user code can import dev.topo.* classes
    fs::path runtimeJar = fs::path(exeDir) / "topo-runtime.jar";
    if (fs::exists(runtimeJar)) {
        char sep = topo::platform::IsWindows ? ';' : ':';
        if (userClasspath.empty())
            userClasspath = runtimeJar.string();
        else
            userClasspath = runtimeJar.string() + sep + userClasspath;
    } else {
        if (req.config.parallelCfg.isEnabled() ||
            req.config.dataLayoutCfg.isEnabled() ||
            req.config.typeNarrowingCfg.isEnabled() ||
            req.config.prefetchCfg.isEnabled()) {
            std::cerr << "warning: topo-runtime.jar not found at "
                      << runtimeJar.string()
                      << "; transforms requiring dev.topo.* runtime classes will produce "
                      << "JARs that fail at run time\n";
        }
    }

    fs::path classDir = tempDir / "classes";
    auto compileResult =
        topo::jvm::compileJava(req.sources, userClasspath, classDir.string(),
                               targetVersion, req.config.buildMode,
                               javaHome, req.verbose);

    if (compileResult.exitCode != 0) {
        std::cerr << "error: javac compilation failed (exit " << compileResult.exitCode << ")\n";
        return 1;
    }

    // ================================================================
    // Step 2: Verify Topo/bytecode consistency
    // ================================================================
    if (!req.config.noVerify) {
        std::cerr << "[2/4] Verifying bytecode against declarations...\n";

        std::string java = topo::jvm::resolveJavaTool("java", javaHome);
        std::vector<std::string> verifyArgs;
        for (const auto& arg : jvmArgs)
            verifyArgs.push_back(arg);
        verifyArgs.push_back("-jar");
        verifyArgs.push_back(transformJar.string());
        verifyArgs.push_back("--verify");
        verifyArgs.push_back("--classes");
        verifyArgs.push_back(classDir.string());
        verifyArgs.push_back("--metadata");
        verifyArgs.push_back(metadataPath.string());
        verifyArgs.push_back("--config");
        verifyArgs.push_back(configPath.string());

        auto verifyResult = topo::platform::runProcess(java, verifyArgs, req.verbose);
        if (verifyResult.exitCode != 0) {
            std::cerr << "error: bytecode verification failed (exit " << verifyResult.exitCode << ")\n";
            if (!req.config.warnOnly) return 1;
            std::cerr << "      (continuing due to --warn-only)\n";
        }
    }

    // ================================================================
    // Step 3: Apply bytecode transforms
    // ================================================================
    std::cerr << "[3/4] Applying bytecode transforms...\n";

    fs::path transformedDir = tempDir / "transformed";
    {
        std::string java = topo::jvm::resolveJavaTool("java", javaHome);
        std::vector<std::string> transformArgs;
        for (const auto& arg : jvmArgs)
            transformArgs.push_back(arg);
        transformArgs.push_back("-jar");
        transformArgs.push_back(transformJar.string());
        transformArgs.push_back("--transform");
        transformArgs.push_back("--classes");
        transformArgs.push_back(classDir.string());
        transformArgs.push_back("--metadata");
        transformArgs.push_back(metadataPath.string());
        transformArgs.push_back("--config");
        transformArgs.push_back(configPath.string());
        transformArgs.push_back("--output");
        transformArgs.push_back(transformedDir.string());

        // Per-Pass sidecar directory (sidecar protocol).
        // The JVM transformer writes `<sidecar-dir>/<PassName>.json` per
        // judging Pass that accumulated decisions. Compute the directory from
        // the final output JAR path: `<output>.jar.topo-passes/`.
        // Sidecar writes are non-load-bearing — failure logs to stderr but
        // does not break the build.
        fs::path sidecarDir = fs::path(req.outputPath + ".topo-passes");
        transformArgs.push_back("--sidecar-dir");
        transformArgs.push_back(sidecarDir.string());

        auto transformResult = topo::platform::runProcess(java, transformArgs, req.verbose);
        if (transformResult.exitCode != 0) {
            std::cerr << "error: bytecode transformation failed (exit " << transformResult.exitCode << ")\n";
            return 1;
        }
    }

    // ================================================================
    // Step 3b: Bundle SDK runtime classes into transformed output
    // ================================================================
    {
        fs::path runtimeJar = fs::path(exeDir) / "topo-runtime.jar";
        if (fs::exists(runtimeJar)) {
            std::string jarTool = topo::jvm::resolveJavaTool("jar", javaHome);
            std::vector<std::string> extractArgs = {"xf", runtimeJar.string()};
            auto extractResult = topo::platform::runProcessCapture(jarTool, extractArgs, transformedDir.string());
            if (extractResult.exitCode != 0 && req.verbose) {
                std::cerr << "      warning: failed to extract SDK runtime\n";
            }
        }
    }

    // ================================================================
    // Step 3c: Post-transform verification
    // ================================================================
    if (!req.config.noVerify) {
        std::cerr << "      Verifying post-transform consistency...\n";

        std::string java = topo::jvm::resolveJavaTool("java", javaHome);
        std::vector<std::string> postVerifyArgs;
        for (const auto& arg : jvmArgs)
            postVerifyArgs.push_back(arg);
        postVerifyArgs.push_back("-jar");
        postVerifyArgs.push_back(transformJar.string());
        postVerifyArgs.push_back("--post-verify");
        postVerifyArgs.push_back("--classes");
        postVerifyArgs.push_back(transformedDir.string());
        postVerifyArgs.push_back("--metadata");
        postVerifyArgs.push_back(metadataPath.string());
        postVerifyArgs.push_back("--config");
        postVerifyArgs.push_back(configPath.string());

        auto postVerifyResult = topo::platform::runProcessCapture(java, postVerifyArgs, req.verbose);
        if (postVerifyResult.exitCode != 0) {
            std::cerr << "error: post-transform verification failed\n";
            if (!postVerifyResult.stdoutOutput.empty())
                std::cerr << postVerifyResult.stdoutOutput << "\n";
            if (!req.config.warnOnly) return 1;
            std::cerr << "      (continuing due to --warn-only)\n";
        } else if (req.verbose) {
            std::cerr << "      Post-transform verification passed\n";
        }
    }

    // ================================================================
    // Step 4: Package .class files into output JAR
    // ================================================================
    std::cerr << "[4/4] Packaging JAR...\n";

    std::string mainClass;
    if (req.backendExtras.contains("mainClass")) mainClass = req.backendExtras["mainClass"].get<std::string>();

    // Auto-detect mainClass if not specified
    if (mainClass.empty()) {
        // Strategy 1: Scan metadata classes for a member function named "main"
        nlohmann::json metaJson = topo::serializeSymbolTable(req.symbolTable);
        if (metaJson.contains("classes")) {
            for (const auto& [name, cls] : metaJson["classes"].items()) {
                if (!cls.contains("memberFunctions")) continue;
                for (const auto& mf : cls["memberFunctions"]) {
                    std::string fn = mf.get<std::string>();
                    if (fn == "main" || (fn.size() > 6 && fn.substr(fn.size() - 6) == "::main")) {
                        // Convert "app::Main" -> "app.Main"
                        mainClass = name;
                        size_t pos = 0;
                        while ((pos = mainClass.find("::", pos)) != std::string::npos) {
                            mainClass.replace(pos, 2, ".");
                        }
                        break;
                    }
                }
                if (!mainClass.empty()) break;
            }
        }

        // Strategy 2: Scan compiled .class files for main([Ljava/lang/String;)V
        if (mainClass.empty() && fs::exists(classDir)) {
            std::string javapTool = topo::jvm::resolveJavaTool("javap", javaHome);
            for (const auto& entry : fs::recursive_directory_iterator(classDir)) {
                if (!entry.is_regular_file() || entry.path().extension() != ".class") continue;
                std::vector<std::string> javapArgs = {"-p", "-s", entry.path().string()};
                auto javapResult = topo::platform::runProcessCapture(javapTool, javapArgs, false);
                if (javapResult.exitCode == 0 &&
                    javapResult.stdoutOutput.find("([Ljava/lang/String;)V") != std::string::npos) {
                    // Derive fully-qualified class name from path relative to classDir
                    auto relPath = fs::relative(entry.path(), classDir);
                    std::string cls = relPath.string();
                    // Remove .class extension
                    if (cls.size() > 6) cls = cls.substr(0, cls.size() - 6);
                    // Convert path separators to dots
                    std::replace(cls.begin(), cls.end(), '/', '.');
                    std::replace(cls.begin(), cls.end(), '\\', '.');
                    mainClass = cls;
                    break;
                }
            }
        }

        if (!mainClass.empty() && req.verbose) std::cerr << "      auto-detected mainClass: " << mainClass << "\n";
    }

    auto packageResult =
        topo::jvm::packageJar(transformedDir.string(), req.outputPath, mainClass, javaHome, req.verbose);

    if (packageResult.exitCode != 0) {
        std::cerr << "error: JAR packaging failed (exit " << packageResult.exitCode << ")\n";
        return 1;
    }

    std::cerr << "      output: " << req.outputPath << "\n";

    // Preserve metadata file alongside JAR for decompiler access
    if (keepMetadata) {
        fs::path outputDir = fs::path(req.outputPath).parent_path();
        fs::path destMetadata = outputDir / "topo-metadata.json";
        if (fs::exists(metadataPath) && metadataPath != destMetadata) {
            std::error_code copyEc;
            fs::copy_file(metadataPath, destMetadata, fs::copy_options::overwrite_existing, copyEc);
            if (copyEc) {
                std::cerr << "warning: failed to copy metadata to '" << destMetadata.string()
                          << "': " << copyEc.message() << "\n";
            } else if (req.verbose) {
                std::cerr << "      metadata preserved: " << destMetadata.string() << "\n";
            }
        }
    }

    return 0;
}
