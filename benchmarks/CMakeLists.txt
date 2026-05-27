# topo-jvm benchmarks — 14 per-feature benchmark projects.
#
# Gated by TOPO_JVM_BUILD_E2E at the top level. When ON, this subdirectory
# registers each subproject's Topo.toml as a bench artifact so topo-build
# (imported from topo-cli) pre-builds the four variants (vanilla / topo
# base / topo auto / topo forced) before the e2e suite consumes them.
#
# Each subproject is a .topo-only fixture (Topo.toml + topo/main.topo +
# src/main/java/) — no per-subproject CMakeLists.txt is needed for the
# Java path; the vanilla-Java build flavour is handled by
# TopoBenchArtifactsDriver via the VANILLA_JAVA option.

if(NOT TARGET topo::cli::topo-build)
    message(FATAL_ERROR
        "topo-jvm benchmarks: topo::cli::topo-build not imported — "
        "ensure find_package(topo-cli CONFIG) at top level succeeded.")
endif()

include(${PROJECT_SOURCE_DIR}/cmake/TopoBenchArtifacts.cmake)

# Excludes mirror the monorepo's lift list — orphan benchmark projects
# whose Topo.toml exists but whose feature has no associated TEST_F in
# the current JvmBenchmark/JvmEquivalence shape. Keep in sync with
# monorepo CMakeLists.txt::topo_register_all_bench_artifacts.
topo_register_all_bench_artifacts(
    "${CMAKE_CURRENT_SOURCE_DIR}"
    VANILLA_JAVA
    EXCLUDE
        data_layout_freq
        lifetime_alloc)
