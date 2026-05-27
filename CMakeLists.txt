cmake_minimum_required(VERSION 3.20)

project(topo-jvm
    VERSION 0.1.0
    DESCRIPTION "Topo JVM backend — ASM bytecode transformer, JVMLifter, build tool (zero LLVM dependency)"
    HOMEPAGE_URL "https://github.com/topo-lang/topo-jvm"
    LANGUAGES CXX
)

include(GNUInstallDirs)
include(CMakePackageConfigHelpers)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(TopoJvmCompilerFlags)

# ── Upstream Topo packages ──────────────────────────────
find_package(topo-core CONFIG REQUIRED)

# ── External dependencies ───────────────────────────────
find_package(nlohmann_json CONFIG REQUIRED)

# ── Options ─────────────────────────────────────────────
# topo-build-jvm-java reverse-links topo::lang-java::TopoJavaDriver,
# which IS in topo-lang-java's DEFAULT install set (unlike LLVM's case
# where TopoCppDriver / TopoRustDriver are gated). So this gate defaults
# ON.
option(TOPO_JVM_BUILD_TOOLS
    "Build topo-build-jvm-java (needs topo-lang-java)"
    ON)

# Build the ASM bytecode transformer (Gradle Java project under
# transform/, produces topo-transform.jar with 15 bytecode passes).
option(TOPO_JVM_BUILD_TRANSFORM
    "Build the ASM bytecode transformer (Gradle Java project)"
    ON)

# Build the JVMLifter (Java bytecode -> TranspileModel). Gradle-built JAR.
option(TOPO_JVM_BUILD_DECOMPILE
    "Build the JVMLifter decompiler (Gradle Java project)"
    ON)

option(TOPO_JVM_BUILD_TESTS "Build unit + e2e tests" ON)

# E2E suites + 14 benchmark subprojects.
#
# Default OFF — these require the topo-build CLI (lives in topo-cli, not
# yet a hard dependency of topo-jvm core) plus the topo-lang-java JVM
# install prefix (for topo-runtime.jar deployment) and the topo-llvm
# benchmarks tree (for cross-backend equivalence comparisons).
#
# When ON we check for these prerequisites with find_package(topo-cli
# QUIET) + find_package(topo-lang-java QUIET) and skip cleanly with a
# STATUS message if missing.
option(TOPO_JVM_BUILD_E2E
    "Build e2e (topo-e2e-java / -jvm-bench / -jvm-equiv / -jvm-tomlswap) + 14 benchmark subprojects"
    OFF)

# ── Tooling auto-detection (gradle/javac for Gradle subprojects) ──
# Auto-detect once: prefer a system `gradle` binary, else the bundled
# `gradlew` wrapper each Gradle subproject ships. If neither resolves,
# gate the corresponding subproject OFF rather than fail configure.
set(_TOPO_JVM_GRADLEW_TRANSFORM "${CMAKE_CURRENT_SOURCE_DIR}/transform/gradlew")
set(_TOPO_JVM_GRADLEW_DECOMPILE "${CMAKE_CURRENT_SOURCE_DIR}/decompile/gradlew")

find_program(_TOPO_GRADLE gradle)

if(TOPO_JVM_BUILD_TRANSFORM)
    if(NOT _TOPO_GRADLE AND NOT EXISTS "${_TOPO_JVM_GRADLEW_TRANSFORM}")
        message(STATUS "topo-jvm: neither system gradle nor transform/gradlew found; disabling TOPO_JVM_BUILD_TRANSFORM")
        set(TOPO_JVM_BUILD_TRANSFORM OFF CACHE BOOL "" FORCE)
    endif()
endif()

if(TOPO_JVM_BUILD_DECOMPILE)
    if(NOT _TOPO_GRADLE AND NOT EXISTS "${_TOPO_JVM_GRADLEW_DECOMPILE}")
        message(STATUS "topo-jvm: neither system gradle nor decompile/gradlew found; disabling TOPO_JVM_BUILD_DECOMPILE")
        set(TOPO_JVM_BUILD_DECOMPILE OFF CACHE BOOL "" FORCE)
    endif()
endif()

# Probe a usable JAVA_HOME (TOPO_GRADLE_JAVA_HOME wins, then JAVA_HOME env).
if(TOPO_JVM_BUILD_TRANSFORM OR TOPO_JVM_BUILD_DECOMPILE)
    if(NOT DEFINED TOPO_GRADLE_JAVA_HOME)
        if(DEFINED ENV{TOPO_GRADLE_JAVA_HOME})
            set(TOPO_GRADLE_JAVA_HOME "$ENV{TOPO_GRADLE_JAVA_HOME}")
        elseif(DEFINED ENV{JAVA_HOME})
            set(TOPO_GRADLE_JAVA_HOME "$ENV{JAVA_HOME}")
        endif()
    endif()

    if(TOPO_GRADLE_JAVA_HOME)
        set(_JVM_GRADLE_ENV
            ${CMAKE_COMMAND} -E env "JAVA_HOME=${TOPO_GRADLE_JAVA_HOME}")
        message(STATUS "topo-jvm: Gradle invocations will use JAVA_HOME=${TOPO_GRADLE_JAVA_HOME}")
    else()
        set(_JVM_GRADLE_ENV ${CMAKE_COMMAND} -E env)
        message(STATUS "topo-jvm: TOPO_GRADLE_JAVA_HOME unset; Gradle will resolve its own JAVA_HOME")
    endif()
endif()

if(TOPO_JVM_BUILD_TOOLS)
    find_package(topo-lang-java CONFIG REQUIRED)
endif()

# ── deploy-topo-runtime-java pre-flight ────────────────
# Decide BEFORE add_subdirectory(tools/topo-build-jvm-java) whether the
# subdir should wire a POST_BUILD step copying topo-runtime.jar next to
# the binary. The decision needs topo-lang-java's JVM-side install
# (topo-runtime.jar) — wired here so the subdir scope sees the decision.
# (Without this pre-flight the deploy lived after the subdir was added,
# putting TARGET_FILE_DIR's referenced target in a different scope and
# triggering the CMake "No target" generator-time error.)
if(TOPO_JVM_BUILD_TOOLS AND TOPO_JVM_BUILD_E2E)
    find_path(TOPO_LANG_JAVA_RUNTIME_JAR_DIR
        NAMES topo-runtime.jar
        PATHS ${CMAKE_PREFIX_PATH}
        PATH_SUFFIXES share/topo-lang-java/lib
        DOC "Directory containing topo-runtime.jar from topo-lang-java install")
    if(TOPO_LANG_JAVA_RUNTIME_JAR_DIR)
        set(_RUNTIME_JAR_SRC "${TOPO_LANG_JAVA_RUNTIME_JAR_DIR}/topo-runtime.jar"
            CACHE INTERNAL "topo-runtime.jar source for deploy-topo-runtime-java")
        set(TOPO_JVM_DEPLOY_RUNTIME_JAR ON CACHE INTERNAL
            "Trigger deploy-topo-runtime-java post-build step in topo-build-jvm-java")
        message(STATUS "topo-jvm: deploy-topo-runtime-java enabled "
            "(source: ${_RUNTIME_JAR_SRC})")
    else()
        message(STATUS "topo-jvm: deploy-topo-runtime-java disabled — "
            "topo-runtime.jar not found via CMAKE_PREFIX_PATH. "
            "Configure topo-lang-java with TOPO_LANG_JAVA_ENABLE_JVM_BACKEND=ON "
            "and install it on CMAKE_PREFIX_PATH to enable.")
    endif()
endif()

# ── Components ──────────────────────────────────────────
# topo-build-jvm-java (C++ binary). Reverse-links TopoJavaDriver from
# topo-lang-java's default install set.
if(TOPO_JVM_BUILD_TOOLS)
    add_subdirectory(tools/topo-build-jvm-java)
endif()

# Gradle-built Java transformer (JAR artifact) — 15 ASM bytecode passes.
if(TOPO_JVM_BUILD_TRANSFORM)
    if(_TOPO_GRADLE)
        set(_TRANSFORM_GRADLE "${_TOPO_GRADLE}")
    else()
        set(_TRANSFORM_GRADLE "${_TOPO_JVM_GRADLEW_TRANSFORM}")
    endif()

    set(_TRANSFORM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/transform)
    set(_TRANSFORM_JAR ${_TRANSFORM_DIR}/build/libs/topo-transform.jar)
    file(GLOB_RECURSE _TRANSFORM_JAVA_SRC CONFIGURE_DEPENDS
        ${_TRANSFORM_DIR}/src/main/java/*.java)
    add_custom_command(
        OUTPUT ${_TRANSFORM_JAR}
        COMMAND ${_JVM_GRADLE_ENV} ${_TRANSFORM_GRADLE} jar
        WORKING_DIRECTORY ${_TRANSFORM_DIR}
        DEPENDS ${_TRANSFORM_JAVA_SRC} ${_TRANSFORM_DIR}/build.gradle.kts
        COMMENT "Building topo-transform.jar via Gradle"
    )
    add_custom_target(topo-transform-jvm ALL DEPENDS ${_TRANSFORM_JAR})

    # topo-build-jvm-java needs the transform jar at runtime — stage it
    # next to the binary so direct invocation works from the build tree.
    if(TARGET topo-build-jvm-java)
        add_dependencies(topo-build-jvm-java topo-transform-jvm)
        add_custom_command(TARGET topo-transform-jvm POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                ${_TRANSFORM_JAR}
                $<TARGET_FILE_DIR:topo-build-jvm-java>/topo-transform.jar
            COMMENT "Staging topo-transform.jar next to topo-build-jvm-java"
        )
    endif()

    install(FILES ${_TRANSFORM_JAR}
        DESTINATION ${CMAKE_INSTALL_DATADIR}/topo-jvm/lib
        COMPONENT topo-jvm-transform)
endif()

# Gradle-built JVMLifter (Java bytecode -> TranspileModel) — JAR artifact.
if(TOPO_JVM_BUILD_DECOMPILE)
    if(_TOPO_GRADLE)
        set(_DECOMPILE_GRADLE "${_TOPO_GRADLE}")
    else()
        set(_DECOMPILE_GRADLE "${_TOPO_JVM_GRADLEW_DECOMPILE}")
    endif()

    set(_DECOMPILE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/decompile)
    set(_DECOMPILE_JAR ${_DECOMPILE_DIR}/build/libs/topo-decompile-jvm-1.0.0.jar)
    file(GLOB_RECURSE _DECOMPILE_JAVA_SRC CONFIGURE_DEPENDS
        ${_DECOMPILE_DIR}/src/main/java/*.java)
    add_custom_command(
        OUTPUT ${_DECOMPILE_JAR}
        COMMAND ${_JVM_GRADLE_ENV} ${_DECOMPILE_GRADLE} jar
        WORKING_DIRECTORY ${_DECOMPILE_DIR}
        DEPENDS ${_DECOMPILE_JAVA_SRC} ${_DECOMPILE_DIR}/build.gradle.kts
        COMMENT "Building topo-decompile-jvm-1.0.0.jar via Gradle"
    )
    add_custom_target(topo-decompile-jvm ALL DEPENDS ${_DECOMPILE_JAR})

    install(FILES ${_DECOMPILE_JAR}
        DESTINATION ${CMAKE_INSTALL_DATADIR}/topo-jvm/lib
        COMPONENT topo-jvm-decompile)
endif()

# ── Install / export ────────────────────────────────────
install(EXPORT topo-jvmTargets
    FILE topo-jvmTargets.cmake
    NAMESPACE topo::jvm::
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/topo-jvm
)

configure_package_config_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/cmake/topo-jvmConfig.cmake.in
    ${CMAKE_CURRENT_BINARY_DIR}/topo-jvmConfig.cmake
    INSTALL_DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/topo-jvm
)

write_basic_package_version_file(
    ${CMAKE_CURRENT_BINARY_DIR}/topo-jvmConfigVersion.cmake
    VERSION       ${PROJECT_VERSION}
    COMPATIBILITY SameMajorVersion
)

install(FILES
    ${CMAKE_CURRENT_BINARY_DIR}/topo-jvmConfig.cmake
    ${CMAKE_CURRENT_BINARY_DIR}/topo-jvmConfigVersion.cmake
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/topo-jvm
)

# ── Tests ───────────────────────────────────────────────
if(TOPO_JVM_BUILD_TESTS AND PROJECT_IS_TOP_LEVEL)
    enable_testing()
    include(FetchContent)
    FetchContent_Declare(googletest
        URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.tar.gz)
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(googletest)
    set(CMAKE_GTEST_DISCOVER_TESTS_DISCOVERY_MODE PRE_TEST)
    include(GoogleTest)

    add_subdirectory(test)
endif()

# ── E2E + benchmarks (gated) ────────────────────────────
#
# Sequenced AFTER the test block so enable_testing() + GTest fetch have
# already run when e2e's add_executable / gtest_discover_tests fire.
#
# Resolves three optional upstream prefixes:
#   - topo-cli            (topo::cli::topo-build executable for e2e)
#   - topo-lang-java      (topo-runtime.jar + topo-lang-java install dir
#                          for deploy-topo-runtime-java)
#   - TOPO_LLVM_BENCHMARKS_DIR (optional — JvmBenchmarkTests/
#                          JvmEquivalenceTests reference it; SKIPped if
#                          unset)
#
# Missing topo-cli ⇒ skip e2e + benchmarks cleanly (STATUS message).
# Missing topo-lang-java with JVM_BACKEND ON ⇒ skip deploy-topo-runtime-java
# (the rest of the e2e suite still runs; topo-build-jvm-java just won't
# have topo-runtime.jar staged next to it, which only affects user
# workflows that link the runtime).
if(TOPO_JVM_BUILD_E2E AND TOPO_JVM_BUILD_TESTS AND PROJECT_IS_TOP_LEVEL)
    find_package(topo-cli CONFIG)

    # Probe a runtime JDK ≥21 for the e2e suite. topo-transform.jar is
    # compiled to JDK 21 class files (Gradle 8.14's auto-downloaded
    # toolchain), so `java -jar topo-transform.jar` needs a ≥21 JRE.
    # The macOS java_home helper handles version lookup; on Linux we
    # fall back to whatever JAVA_HOME / `java` resolves at test time.
    set(TOPO_E2E_JAVA_HOME "")
    if(APPLE)
        foreach(_JDK_VER 21 22 23 25 26)
            execute_process(
                COMMAND /usr/libexec/java_home -v ${_JDK_VER}
                OUTPUT_VARIABLE _RUNTIME_JDK
                OUTPUT_STRIP_TRAILING_WHITESPACE
                ERROR_QUIET
                RESULT_VARIABLE _RT_RC)
            if(_RT_RC EQUAL 0 AND EXISTS "${_RUNTIME_JDK}/bin/java")
                set(TOPO_E2E_JAVA_HOME "${_RUNTIME_JDK}")
                break()
            endif()
        endforeach()
    endif()
    if(NOT TOPO_E2E_JAVA_HOME AND DEFINED ENV{JAVA_HOME})
        set(TOPO_E2E_JAVA_HOME "$ENV{JAVA_HOME}")
    endif()
    if(TOPO_E2E_JAVA_HOME)
        message(STATUS "topo-jvm e2e: runtime JDK = ${TOPO_E2E_JAVA_HOME}")
    else()
        message(STATUS "topo-jvm e2e: no JDK ≥21 found — e2e cases will use "
            "the system JAVA_HOME at run time; some Java fixtures may fail "
            "if it points to a <21 JDK.")
    endif()

    if(NOT TARGET topo::cli::topo-build)
        message(STATUS
            "topo-jvm: TOPO_JVM_BUILD_E2E=ON but topo-cli not found on "
            "CMAKE_PREFIX_PATH (provides topo::cli::topo-build). "
            "E2E + benchmarks SKIPPED. Install topo-cli and re-configure.")
    else()
        set(TOPO_BUILD_TARGET topo::cli::topo-build CACHE INTERNAL
            "topo-build imported target")

        # (deploy-topo-runtime-java is wired in the pre-flight block above,
        # before add_subdirectory(tools/topo-build-jvm-java), so the
        # POST_BUILD copy step sits in the same scope as the executable
        # target.)

        # TOPO_LLVM_BENCHMARKS_DIR cross-backend reference. Optional —
        # JvmEquivalenceTests use it for vanilla-C++ comparison baselines.
        # When topo-llvm's install prefix isn't on CMAKE_PREFIX_PATH, fall
        # back to the local JVM benchmarks tree (the harness requires a
        # real directory exist; Java-only tests don't consume LLVM paths).
        if(NOT DEFINED TOPO_LLVM_BENCHMARKS_DIR OR NOT EXISTS "${TOPO_LLVM_BENCHMARKS_DIR}")
            set(TOPO_LLVM_BENCHMARKS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/benchmarks"
                CACHE PATH "Path to topo-llvm benchmark tree (optional; falls back to JVM tree)" FORCE)
        endif()
        if(NOT DEFINED TOPO_LLVM_BINDIR)
            set(TOPO_LLVM_BINDIR "${CMAKE_CURRENT_SOURCE_DIR}/benchmarks"
                CACHE PATH "Path to topo-llvm bin tree (optional placeholder)" FORCE)
        endif()

        # 14 benchmark subprojects: register each as a bench artifact so
        # topo-build pre-builds variants for the e2e suite to consume.
        add_subdirectory(benchmarks)

        # E2E test binaries (4): JavaFunctional / JvmBenchmark /
        # JvmEquivalence / TopoTomlSwap.
        add_subdirectory(test/e2e)

        # Finalize the bench-artifacts aggregate target so the
        # FIXTURES_SETUP test below can drive it.
        topo_finalize_bench_artifacts_target()
    endif()
endif()
