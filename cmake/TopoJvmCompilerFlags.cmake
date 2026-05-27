# TopoJvmCompilerFlags.cmake — standalone compiler-flag helpers for
# topo-jvm. Mirrors the monorepo cmake/TopoCompilerFlags.cmake but
# scoped to the JVM backend layer (zero LLVM dependency).

# RPATH configuration for Unix shared library builds.
if(NOT WIN32)
    set(CMAKE_INSTALL_RPATH_USE_LINK_PATH TRUE)
    if(APPLE)
        set(CMAKE_MACOSX_RPATH ON)
    endif()
endif()

# ── Sanitizer support ────────────────────────────────────
set(TOPO_JVM_SANITIZER "" CACHE STRING
    "Enable sanitizers (address, undefined, thread, memory)")

function(topo_jvm_apply_sanitizer target)
    if(NOT TOPO_JVM_SANITIZER)
        return()
    endif()
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(${target}
            PRIVATE -fsanitize=${TOPO_JVM_SANITIZER} -fno-omit-frame-pointer)
        target_link_options(${target}
            PRIVATE -fsanitize=${TOPO_JVM_SANITIZER})
    endif()
endfunction()

# Compiler flag base — applied to every C++ target in topo-jvm.
function(topo_set_compiler_flags target)
    target_compile_features(${target} PUBLIC cxx_std_17)
    set_target_properties(${target} PROPERTIES CXX_EXTENSIONS OFF)
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic)
    elseif(MSVC)
        target_compile_options(${target} PRIVATE /W4)
    endif()
    topo_jvm_apply_sanitizer(${target})
endfunction()

# PCH helpers — no-ops in standalone (no project-wide PCH host).
if(NOT COMMAND topo_apply_std_pch)
    function(topo_apply_std_pch target)
    endfunction()
endif()
