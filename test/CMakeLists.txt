# MinGW on GHA: cached upstream topo-install may have been linked against
# a different libstdc++/libgcc ABI than C:\mingw64\bin\ currently provides,
# tripping STATUS_ENTRYPOINT_NOT_FOUND (0xc0000139) when gtest_discover_tests
# launches each binary. Static-link the C++ runtime for every test exe in
# this directory so they're hermetic.
if(MINGW)
    add_link_options(-static -static-libgcc -static-libstdc++)
endif()

# topo-jvm test suite.
#
# Unit tests (test/unit/) are standalone-friendly — they spawn the
# topo-build-jvm-java tool with hand-crafted JSON request files to
# exercise the CLI's per-value backendExtras validation. They only
# require the tool to be built (TOPO_JVM_BUILD_TOOLS=ON).
#
# E2E tests (test/e2e/) are deferred — they depend on the topo-build
# CLI (lives in topo-cli, not yet lifted), monorepo cmake helpers
# (TopoE2eProjectLocks.cmake, TopoBenchCategoryLabels.cmake), and the
# TOPO_LLVM_BENCHMARKS_DIR / TOPO_CORE_BUILD_FIXTURES_DIR fixture
# directories, so they are gated off until those land.

if(TOPO_JVM_BUILD_TOOLS)
    add_subdirectory(unit)
endif()
