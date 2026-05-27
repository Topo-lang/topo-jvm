# topo-build-jvm-java CLI input-trust unit tests
#
# Exercises the CLI's per-value backendExtras validation by spawning
# topo-build-jvm-java with hand-crafted request JSON files and
# asserting the exit code + stderr label. The tool process is fast —
# no javac is reached because validation fires before the build steps.
#
# Windows-skipped: same MinGW-host subprocess pattern that hits
# tpm-cli, topo-build-python-input-trust, and ts-input-trust —
# spawn(tool) never returns on GHA windows-2022 while ubuntu/macOS
# finish in milliseconds.
if(NOT WIN32)
    add_executable(topo-build-jvm-java-input-trust-tests
        BackendExtrasInputTrustTest.cpp
    )
    target_link_libraries(topo-build-jvm-java-input-trust-tests PRIVATE
        topo::core::TopoPlatform
        nlohmann_json::nlohmann_json
        GTest::gtest_main
    )
    target_compile_definitions(topo-build-jvm-java-input-trust-tests PRIVATE
        TOPO_BUILD_JVM_JAVA_EXE="$<TARGET_FILE:topo-build-jvm-java>"
    )
    add_dependencies(topo-build-jvm-java-input-trust-tests topo-build-jvm-java)
    topo_set_compiler_flags(topo-build-jvm-java-input-trust-tests)

    gtest_discover_tests(topo-build-jvm-java-input-trust-tests
        PROPERTIES LABELS "toolchain;jvm" TIMEOUT 30)
endif()
