// Regression tests for TopoTomlSwap — RAII guard around the
// Topo.toml ↔ Topo-{base,forced}.toml swap pattern that the JVM
// benchmark / equivalence suite uses.
//
// Resource-lifecycle fix: the prior ad-hoc swap left Topo.toml
// permanently swapped if the build aborted mid-scope. These tests
// verify the destructor always restores the original Topo.toml even
// when the swap-using scope exits via exception or early return.

#include "E2eHarness.h"

#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <string>

#include <gtest/gtest.h>

namespace fs = std::filesystem;
using topo::test::e2e::TopoTomlSwap;

namespace {

fs::path makeProject(const fs::path& parent, const std::string& name,
                     const std::string& topoTomlContent,
                     const std::string& altName,
                     const std::string& altContent) {
    fs::path projDir = parent / name;
    fs::create_directories(projDir);
    {
        std::ofstream f(projDir / "Topo.toml");
        f << topoTomlContent;
    }
    {
        std::ofstream f(projDir / altName);
        f << altContent;
    }
    return projDir;
}

std::string readFile(const fs::path& p) {
    std::ifstream f(p);
    std::string s((std::istreambuf_iterator<char>(f)),
                  std::istreambuf_iterator<char>());
    return s;
}

} // namespace

class TopoTomlSwapTest : public ::testing::Test {
protected:
    void SetUp() override {
        tempRoot_ = fs::temp_directory_path() /
            ("topo-toml-swap-test-" + std::to_string(::testing::UnitTest::GetInstance()->random_seed()) +
             "-" + std::to_string(reinterpret_cast<uintptr_t>(this)));
        fs::create_directories(tempRoot_);
    }

    void TearDown() override {
        std::error_code ec;
        fs::remove_all(tempRoot_, ec);
    }

    fs::path tempRoot_;
};

TEST_F(TopoTomlSwapTest, RestoresOriginalOnNormalExit) {
    fs::path projDir = makeProject(tempRoot_, "p1", "MAIN",
                                   "Topo-base.toml", "BASE");
    {
        TopoTomlSwap swap(projDir, "Topo-base.toml");
        ASSERT_TRUE(swap.engaged()) << swap.error();
        EXPECT_EQ(readFile(projDir / "Topo.toml"), "BASE");
    }
    EXPECT_EQ(readFile(projDir / "Topo.toml"), "MAIN");
    EXPECT_FALSE(fs::exists(projDir / "Topo.toml.saved"));
}

TEST_F(TopoTomlSwapTest, RestoresOriginalOnException) {
    fs::path projDir = makeProject(tempRoot_, "p2", "MAIN",
                                   "Topo-forced.toml", "FORCED");
    try {
        TopoTomlSwap swap(projDir, "Topo-forced.toml");
        ASSERT_TRUE(swap.engaged()) << swap.error();
        EXPECT_EQ(readFile(projDir / "Topo.toml"), "FORCED");
        throw std::runtime_error("simulated build failure");
    } catch (const std::runtime_error&) {
        // expected — verify the dtor restored regardless
    }
    EXPECT_EQ(readFile(projDir / "Topo.toml"), "MAIN");
    EXPECT_FALSE(fs::exists(projDir / "Topo.toml.saved"));
}

TEST_F(TopoTomlSwapTest, RestoresOriginalOnEarlyReturn) {
    fs::path projDir = makeProject(tempRoot_, "p3", "MAIN",
                                   "Topo-base.toml", "BASE");
    auto doWork = [&]() -> int {
        TopoTomlSwap swap(projDir, "Topo-base.toml");
        if (!swap.engaged()) return -1;
        // Early return — mirrors gtest ASSERT or a build error path.
        return 0;
    };
    EXPECT_EQ(doWork(), 0);
    EXPECT_EQ(readFile(projDir / "Topo.toml"), "MAIN");
    EXPECT_FALSE(fs::exists(projDir / "Topo.toml.saved"));
}

TEST_F(TopoTomlSwapTest, NotEngagedWhenAltMissing) {
    fs::path projDir = tempRoot_ / "p4";
    fs::create_directories(projDir);
    {
        std::ofstream f(projDir / "Topo.toml");
        f << "MAIN";
    }
    TopoTomlSwap swap(projDir, "Topo-missing.toml");
    EXPECT_FALSE(swap.engaged());
    EXPECT_FALSE(swap.error().empty());
    EXPECT_EQ(readFile(projDir / "Topo.toml"), "MAIN");
}
