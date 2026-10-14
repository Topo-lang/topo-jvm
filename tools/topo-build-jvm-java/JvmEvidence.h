#ifndef TOPO_JVM_COMMAND_EVIDENCE_H
#define TOPO_JVM_COMMAND_EVIDENCE_H
#include "JavaDriver.h"
#include "topo/Build/BackendProtocol.h"
#include "topo/Build/ExecutionEvidence.h"
#include "topo/Platform/ToolResolution.h"
#include "topo/Distribution/Sha256.h"
#include <cstdlib>
#include <set>
#include <filesystem>
#include <iostream>

class JvmEvidence {
public:
    static nlohmann::json modelInputDigests(const std::vector<std::string>& paths) {
        namespace fs = std::filesystem;
        std::set<std::string> files;
        std::size_t entries = 0;
        auto addFile = [&](const fs::path& path) {
            if (!fs::is_regular_file(path)) throw std::runtime_error("Java check model input is not a regular file: " + path.string());
            files.insert(fs::canonical(path).string());
        };
        for (const auto& path : paths) {
            if (fs::is_directory(path)) {
                for (const auto& entry : fs::recursive_directory_iterator(path)) {
                    if (++entries > 100000) throw std::runtime_error("Java check model exceeds 100000 input entries");
                    if (entry.is_symlink() && entry.is_directory())
                        throw std::runtime_error("Java check model contains a symlink directory: " + entry.path().string());
                    if (!entry.is_directory()) addFile(entry.path());
                }
            } else addFile(path);
        }
        if (files.size() > 100000) throw std::runtime_error("Java check model exceeds 100000 input files");
        std::uintmax_t bytes = 0;
        nlohmann::json digests = nlohmann::json::object();
        for (const auto& file : files) {
            bytes += fs::file_size(file);
            if (bytes > 16ULL * 1024 * 1024 * 1024) throw std::runtime_error("Java check model exceeds 16 GiB input bytes");
            auto digest = topo::dist::sha256File(file);
            if (!topo::build::evidence::validDigest(digest)) throw std::runtime_error("Cannot hash Java check model input: " + file);
            digests[file] = digest;
        }
        return digests;
    }
    bool enabled;
    topo::jvm::JavaEvidenceOptions options;
    topo::build::evidence::Manifest chain;
    std::string destination, request;
    JvmEvidence(const topo::build::BackendRequest& req, const std::string& requestPath,
                const std::string& javaHome, const std::string& toolsDir,
                const std::vector<std::string>& classpath) : enabled(!req.evidence.mode.empty()), request(requestPath) {
        namespace fs = std::filesystem;
        destination = req.evidence.manifestPath.empty() ? req.outputPath + ".topo-evidence.json" : req.evidence.manifestPath;
        if (!enabled) return;
        auto addRoot = [&](const std::string& id, const fs::path& path) {
            std::error_code ec; auto p = fs::canonical(path, ec);
            if (!ec) options.roots[id] = p;
        };
        addRoot("project", fs::current_path()); addRoot("temp", req.tempDir);
        addRoot("artifact", fs::absolute(req.outputPath).parent_path()); addRoot("tools", toolsDir);
        addRoot("request", fs::absolute(requestPath).parent_path());
        options.extraInputs.push_back(request);
        auto javac = topo::platform::findSpawnableOnPath(topo::jvm::resolveJavaTool("javac", javaHome));
        if (!javac.empty()) addRoot("jdk", fs::canonical(javac).parent_path().parent_path());
        int index = 0;
        for (const auto& path : req.sources) addRoot("source-" + std::to_string(index++),
            fs::is_directory(path) ? fs::path(path) : fs::absolute(path).parent_path());
        index = 0;
        for (const auto& path : classpath) addRoot("dependency-" + std::to_string(index++),
            fs::is_directory(path) ? fs::path(path) : fs::absolute(path).parent_path());
    }
    void addInput(const std::string& path) {
        if (!enabled) return;
        options.extraInputs.push_back(fsAbsolute(path));
        std::error_code ec;
        auto root = std::filesystem::canonical(std::filesystem::absolute(path).parent_path(), ec);
        if (!ec) options.roots["extra-" + std::to_string(options.extraInputs.size())] = root;
    }
    static std::string fsAbsolute(const std::string& path) { return std::filesystem::absolute(path).string(); }
    const topo::jvm::JavaEvidenceOptions* stageOptions(const std::string& path) {
        if (!enabled) return nullptr;
        options.manifestPath = path; options.parents.clear();
        if (!chain.nodes.empty()) options.parents.push_back(chain);
        return &options;
    }
    bool adopt(const std::string& path) {
        if (!enabled) return true;
        std::string error;
        if (!topo::build::evidence::readManifest(path, chain, error)) {
            std::cerr << "error: Java evidence: " << error << "\n"; return false;
        }
        return flush();
    }
    bool flush() const {
        if (!enabled) return true;
        std::string error;
        if (!topo::build::evidence::writeManifest(destination, chain, error)) {
            std::cerr << "error: Java evidence: " << error << "\n"; return false;
        }
        return true;
    }
    topo::platform::ProcessResult run(const std::string& kind, const std::string& executable,
        const std::vector<std::string>& argv, std::vector<std::string> inputs,
        const std::vector<std::string>& outputs, bool verbose, const std::string& cwd = "") {
        namespace fs = std::filesystem;
        struct DirectoryScope { fs::path old = fs::current_path(); ~DirectoryScope(){ std::error_code ec; fs::current_path(old, ec); } } scope;
        if (!cwd.empty()) fs::current_path(cwd);
        if (!enabled) return topo::platform::runProcess(executable, argv, verbose);
        inputs.insert(inputs.end(), options.extraInputs.begin(), options.extraInputs.end());
        auto tool = topo::platform::findSpawnableOnPath(executable);
        if (!tool.empty()) {
            inputs.push_back(tool);
            for (const char* relative : {"release", "lib/modules"}) {
                auto path = fs::path(tool).parent_path().parent_path() / relative;
                if (fs::is_regular_file(path)) inputs.push_back(path.string());
            }
        }
        topo::build::evidence::ExecutionRecorder recorder(options.roots, destination, "topo-build-jvm-java");
        auto result = recorder.run(kind, executable, argv, inputs, outputs, verbose);
        auto manifest = recorder.manifest();
        if (manifest.nodes.empty()) return {-1};
        auto record = manifest.nodes.front(); record.parents = chain.roots;
        topo::build::evidence::sealRecord(record); chain.nodes.push_back(record); chain.roots = {record.id};
        if (!flush()) return {-1};
        return result;
    }
    topo::platform::CapturedProcessResult capture(const std::string& kind, const std::string& executable,
        const std::vector<std::string>& argv, std::vector<std::string> inputs,
        const std::vector<std::string>& outputs, bool verbose) {
        namespace fs = std::filesystem;
        namespace ev = topo::build::evidence;
        if (!enabled) return topo::platform::runProcessCapture(executable, argv, verbose);
        ev::Record record; record.action.kind = kind; record.action.version = "1";
        auto tool = topo::platform::findSpawnableOnPath(executable);
        if (tool.empty()) tool = executable;
        record.action.toolIdentity = "sha256:" + topo::dist::sha256File(tool);
        record.action.argv = argv; record.action.cwd = fs::current_path().string();
        record.action.parameters = {{"capture", "direct-command-observation"}, {"executable", tool}};
        for (const char* name : {"PATH", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "LANG", "LC_ALL"})
            if (const char* value = std::getenv(name)) record.action.environment[name] = value;
        inputs.insert(inputs.end(), options.extraInputs.begin(), options.extraInputs.end()); inputs.push_back(tool);
        for (const char* relative : {"release", "lib/modules"}) {
            auto path = fs::path(tool).parent_path().parent_path() / relative;
            if (fs::is_regular_file(path)) inputs.push_back(path.string());
        }
        std::vector<std::string> errors;
        auto expand = [&](const std::vector<std::string>& paths) {
            std::set<std::string> files;
            std::size_t count = 0;
            auto addFile = [&](const fs::path& path) {
                std::error_code ec;
                auto absolute = fs::absolute(path, ec).lexically_normal();
                auto parent = fs::canonical(absolute.parent_path(), ec);
                if (ec) errors.push_back("Cannot resolve capture input/output: " + path.string());
                else files.insert((parent / absolute.filename()).string());
            };
            for (const auto& path : paths) {
                std::error_code ec;
                auto status = fs::symlink_status(path, ec);
                if (!ec && fs::is_directory(status)) {
                    fs::recursive_directory_iterator it(path, ec), end;
                    while (!ec && it != end) {
                        if (++count > 100000) { errors.push_back("Java capture exceeds 100000 files"); return files; }
                        auto type = it->symlink_status(ec);
                        if (ec) break;
                        if (fs::is_symlink(type) && it->is_directory(ec))
                            errors.push_back("Captured directory contains a symlink directory: " + it->path().string());
                        else if (!fs::is_directory(type)) addFile(it->path());
                        it.increment(ec);
                    }
                    if (ec) errors.push_back("Cannot enumerate captured Java stage inputs");
                } else addFile(path);
            }
            return files;
        };
        auto snapshot = [&](const std::set<std::string>& files, std::vector<ev::FileEntry>& entries) {
            for (const auto& file : files) {
                bool found = false;
                for (const auto& [id, root] : options.roots) {
                    auto relative = fs::path(file).lexically_relative(root).generic_string();
                    if (relative.empty() || relative == ".." || relative.rfind("../", 0) == 0 || relative.front() == '/') continue;
                    ev::FileEntry entry; std::string error;
                    if (ev::snapshotFile(root, id, relative, entry, error)) { entries.push_back(entry); found = true; }
                    else errors.push_back(error);
                    break;
                }
                if (!found) errors.push_back("Capture input/output is outside evidence roots: " + file);
            }
        };
        auto before = expand(inputs);
        snapshot(before, record.action.inputs);
        auto result = topo::platform::runProcessCapture(tool, argv, verbose);
        for (const auto& input : record.action.inputs) {
            std::string error; if (!ev::verifyFile(input, options.roots, error)) errors.push_back(error);
        }
        if (expand(inputs) != before) errors.push_back("Java stage input file set changed during execution");
        snapshot(expand(outputs), record.outputs);
        record.action.parameters["stdoutSha256"] = topo::dist::sha256Hex(result.stdoutOutput);
        record.action.parameters["stderrSha256"] = topo::dist::sha256Hex(result.stderrOutput);
        record.producer = {{"builder", "topo-build-jvm-java"}, {"observation", "process-dispatch"},
            {"isolation", "none"}, {"exitCode", std::to_string(result.exitCode)},
            {"captureDiagnostics", nlohmann::json(errors).dump()}};
        record.complete = false; record.parents = chain.roots; ev::sealRecord(record);
        chain.nodes.push_back(record); chain.roots = {record.id};
        if (!flush()) result.exitCode = -1;
        return result;
    }
};
#endif
