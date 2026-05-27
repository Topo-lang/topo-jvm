# Shared E2E harness -- compiled once as OBJECT library.
#
# Pre-split this source lived at topo-core/test/e2e-harness/E2eHarness.cpp;
# the split sub-repo carves out a local copy at test/e2e/E2eHarness.cpp so
# topo-jvm has no source-tree dependency on the topo-core checkout (the
# harness is generic across backends).
#
# topo::cli::topo-build comes from find_package(topo-cli) at top level.
if(NOT TARGET ${TOPO_BUILD_TARGET})
    message(FATAL_ERROR
        "topo-jvm e2e: TOPO_BUILD_TARGET=${TOPO_BUILD_TARGET} not defined — "
        "ensure find_package(topo-cli CONFIG) at top level succeeded.")
endif()

add_library(E2eHarnessJvm OBJECT E2eHarness.cpp)
target_link_libraries(E2eHarnessJvm PUBLIC topo::core::TopoPlatform GTest::gtest_main)
target_include_directories(E2eHarnessJvm PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
)
target_compile_definitions(E2eHarnessJvm PUBLIC
    TOPO_BUILD_EXE="$<TARGET_FILE:${TOPO_BUILD_TARGET}>"
    TOPO_BENCHMARKS_DIR="${TOPO_LLVM_BENCHMARKS_DIR}"
    TOPO_E2E_FIXTURES_DIR="${TOPO_CORE_BUILD_FIXTURES_DIR}"
    TOPO_LLVM_BINDIR="${TOPO_LLVM_BINDIR}"
    TOPO_JVM_BENCHMARKS_DIR="${PROJECT_SOURCE_DIR}/benchmarks"
    TOPO_JIT_ENGINE_DIR="$<$<TARGET_EXISTS:topo-jit-engine>:$<TARGET_FILE_DIR:topo-jit-engine>>"
)
topo_set_compiler_flags(E2eHarnessJvm)

# --- Java Functional ---
add_executable(topo-e2e-java JavaFunctionalTests.cpp)
target_link_libraries(topo-e2e-java PRIVATE E2eHarnessJvm)
# topo-build is an imported executable (topo::cli::topo-build) — CMake
# disallows add_dependencies(<tgt> <IMPORTED-tgt>). The TOPO_BUILD_EXE
# define references $<TARGET_FILE:...>, giving e2e a generator-expression
# file dependency.

# Split the e2e.java suite into per-TEST_F CTest entries so ctest can
# schedule across them. The legacy single `e2e.java` entry serialised
# every case under one global `topo_e2e_build` lock; per-case entries
# combined with the per-project lock injector below let cases on
# different benchmarks/<project>/ trees run in parallel while same-project
# cases still serialise (the swap/cache mutation that motivated the
# lock is per-project).
include(GoogleTest)
# JAVA_HOME + PATH injection — TOPO_E2E_JAVA_HOME is the JDK ≥21 probed
# at top scope. The Java functional / benchmark / equivalence cases all
# shell out to topo-build → topo-build-jvm-java → javac and need both
# the backend tool on PATH and a JDK ≥21 for the transform jar.
#
# Use a thin wrapper script as TEST_EXECUTOR — gtest_discover_tests's
# TEST_PROPERTIES path splits values on `;`, which mangles a multi-entry
# ENVIRONMENT property; a wrapper sidesteps that by exporting the env
# before exec'ing the test binary.
set(_E2E_TOPO_BUILD_JVM_DIR
    "${CMAKE_BINARY_DIR}/tools/topo-build-jvm-java")
if(TOPO_E2E_JAVA_HOME)
    set(_E2E_PATH "${_E2E_TOPO_BUILD_JVM_DIR}:${TOPO_E2E_JAVA_HOME}/bin:$ENV{PATH}")
else()
    set(_E2E_PATH "${_E2E_TOPO_BUILD_JVM_DIR}:$ENV{PATH}")
endif()
set(_E2E_WRAPPER "${CMAKE_CURRENT_BINARY_DIR}/run-with-jvm-env.sh")
if(TOPO_E2E_JAVA_HOME)
    file(WRITE "${_E2E_WRAPPER}"
"#!/bin/sh
# Auto-generated: export JDK ≥21 + tool PATH for topo-jvm e2e tests.
export JAVA_HOME=\"${TOPO_E2E_JAVA_HOME}\"
export PATH=\"${_E2E_PATH}\"
exec \"$@\"
")
else()
    file(WRITE "${_E2E_WRAPPER}"
"#!/bin/sh
# Auto-generated: export tool PATH for topo-jvm e2e tests (no JDK ≥21 found).
export PATH=\"${_E2E_PATH}\"
exec \"$@\"
")
endif()
file(CHMOD "${_E2E_WRAPPER}" PERMISSIONS
    OWNER_READ OWNER_WRITE OWNER_EXECUTE
    GROUP_READ GROUP_EXECUTE
    WORLD_READ WORLD_EXECUTE)

set_target_properties(topo-e2e-java PROPERTIES TEST_LAUNCHER "${_E2E_WRAPPER}")
gtest_discover_tests(topo-e2e-java DISCOVERY_TIMEOUT 30
    PROPERTIES
        LABELS "e2e;java"
        TIMEOUT 180
)
include(${CMAKE_SOURCE_DIR}/cmake/TopoE2eProjectLocks.cmake)
topo_inject_e2e_project_locks(
    SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/JavaFunctionalTests.cpp"
    FIXTURE JavaFunctional
    BUILD_CALLS topoBuildJvm
    PREFIX topo_e2e_jvm
)

# --- JVM Per-Feature Benchmarks ---
add_executable(topo-e2e-jvm-bench JvmBenchmarkTests.cpp)
target_link_libraries(topo-e2e-jvm-bench PRIVATE E2eHarnessJvm)
# topo-build is an imported executable — see comment above.

# Bench artifact registration (JVM side) moved to root CMakeLists.txt —
# same scoping reason as the LLVM side. `add_custom_command(OUTPUT …)` rules
# must live in a scope that is an ancestor of (or equal to) the scope that
# owns the aggregate target.

# --- CTest fixture for topo-bench-artifacts ---
#
# A single CTest test invokes `cmake --build ... --target topo-bench-artifacts`,
# marked as FIXTURES_SETUP for the fixture `topo_bench_artifacts`. Every
# benchmark test (LLVM + JVM) declares FIXTURES_REQUIRED on the same fixture.
# CTest serialises: setup runs once → all benchmarks run concurrently under
# `ctest -j N`, because no benchmark case touches topo-build or .topo-cache
# anymore (artefacts live under build/artifacts/<project> via fixed output
# names in each Topo*.toml).
#
# The setup test is gated by LABELS=bench_fixture so `ctest -L benchmark`
# does NOT pick it up — it runs only on demand as a FIXTURES_SETUP dep.
# Timeout 1800s: first-run builds all ~50 projects × 4 variants; subsequent
# runs are incremental and finish in seconds.
if(NOT TEST topo_bench_artifacts_build)
    add_test(NAME topo_bench_artifacts_build
        COMMAND ${CMAKE_COMMAND}
                --build ${CMAKE_BINARY_DIR}
                --target topo-bench-artifacts)
    set_tests_properties(topo_bench_artifacts_build PROPERTIES
        FIXTURES_SETUP "topo_bench_artifacts"
        TIMEOUT 1800
        LABELS "bench_fixture")
endif()

# --- per-case JVM benchmark discovery ---
#
# Mirror of the LLVM side — see topo-llvm/test/e2e/CMakeLists.txt for the
# per-case label injection rationale. Category labels are appended by the
# shared post-process script in
# topo-core/cmake/TopoBenchCategoryLabels.cmake.
#
# Split-responsibility locking (mirror of LLVM side):
#   - Pre-build: CMake-level cross-project parallelism via `topo_bench_artifacts`
#     setup fixture.
#   - Measurement: CTest-level serialisation via `topo_bench_measure` lock,
#     shared with LLVM benches so no LLVM + JVM benchmarks race on CPU.
# See `topo-llvm/test/e2e/CMakeLists.txt` for the cross-backend lock pairing.
include(GoogleTest)
set_target_properties(topo-e2e-jvm-bench PROPERTIES TEST_LAUNCHER "${_E2E_WRAPPER}")
gtest_discover_tests(topo-e2e-jvm-bench DISCOVERY_TIMEOUT 30
    PROPERTIES
        LABELS "e2e;perf;benchmark"
        FIXTURES_REQUIRED "topo_bench_artifacts"
        RESOURCE_LOCK "topo_bench_measure"
        TIMEOUT 300
    DISCOVERY_MODE POST_BUILD
)

include(${CMAKE_SOURCE_DIR}/cmake/TopoBenchCategoryLabels.cmake)
topo_inject_bench_category_labels(${CMAKE_CURRENT_SOURCE_DIR}/JvmBenchmarkTests.cpp)

# --- JVM Equivalence ---
add_executable(topo-e2e-jvm-equiv JvmEquivalenceTests.cpp)
target_link_libraries(topo-e2e-jvm-equiv PRIVATE E2eHarnessJvm)
# topo-build is an imported executable — see comment above.

# Split into per-TEST_F entries with per-project locks. JvmEquivalence
# cases share benchmarks/<project>/ working trees + .topo-cache; the
# topo_inject_e2e_project_locks injector emits a per-project lock
# (topo_e2e_jvm_<project>) so same-project cases still serialise the
# TopoTomlSwap RAII while cross-project cases run in parallel. Any
# TEST_F whose body does not match a known build/assert helper gets a
# unique "topo_e2e_jvm_unscoped_<Name>" lock — no collision possible.
include(GoogleTest)
set_target_properties(topo-e2e-jvm-equiv PROPERTIES TEST_LAUNCHER "${_E2E_WRAPPER}")
gtest_discover_tests(topo-e2e-jvm-equiv DISCOVERY_TIMEOUT 30
    PROPERTIES
        LABELS "e2e;equivalence;jvm_equivalence"
        TIMEOUT 300
)
include(${CMAKE_SOURCE_DIR}/cmake/TopoE2eProjectLocks.cmake)
topo_inject_e2e_project_locks(
    SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/JvmEquivalenceTests.cpp"
    FIXTURE JvmEquivalence
    PREFIX topo_e2e_jvm
    BUILD_CALLS topoBuildJvm topoBaseBuildJvm topoForcedBuildJvm vanillaJavaBuild
    ASSERT_CALLS assertJvmBaseEquivalence assertJvmForcedEquivalence assertJvmDefaultEquivalence
)

# Now also gate test/CMakeLists.txt entry so it picks up the e2e dir.

# --- TopoTomlSwap RAII guard unit tests ---
#
# Lightweight unit tests — no topo-build dependency, runs on every CI
# tier. Validates the destructor restores Topo.toml on normal exit,
# exception, and early-return scopes (the swap pattern's three real-
# world exit shapes from the JVM equivalence + benchmark suites).
add_executable(topo-e2e-jvm-tomlswap TopoTomlSwapTest.cpp)
target_link_libraries(topo-e2e-jvm-tomlswap PRIVATE E2eHarnessJvm)

add_test(NAME e2e.jvm_tomlswap COMMAND topo-e2e-jvm-tomlswap)
set_tests_properties(e2e.jvm_tomlswap PROPERTIES
    TIMEOUT 30
    LABELS "toolchain;topo-jvm"
)

