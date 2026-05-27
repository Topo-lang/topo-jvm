add_executable(topo-build-jvm-java
    main.cpp
)

# TopoJavaDriver PUBLIC-propagates TopoPlatform and pulls TopoBuildLib
# transitively. Use the imported namespaced targets from upstream packages.
# topo-core / topo-lang-java install their headers under
# ${CMAKE_INSTALL_INCLUDEDIR} (resolved via the imported targets'
# INTERFACE_INCLUDE_DIRECTORIES); no explicit target_include_directories
# is needed in standalone mode.
target_link_libraries(topo-build-jvm-java PRIVATE
    topo::lang-java::TopoJavaDriver
    topo::core::TopoBuildLib
    nlohmann_json::nlohmann_json
)

topo_set_compiler_flags(topo-build-jvm-java)

install(TARGETS topo-build-jvm-java
    EXPORT topo-jvmTargets
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)

# deploy-topo-runtime-java POST_BUILD hook.
#
# Gated by TOPO_JVM_DEPLOY_RUNTIME_JAR (set by the root CMakeLists when
# TOPO_JVM_BUILD_E2E=ON AND topo-lang-java's runtime.jar was resolved by
# find_package). Lives in this subdirectory so TARGET_FILE_DIR resolves
# in the same scope as the executable target.
if(TOPO_JVM_DEPLOY_RUNTIME_JAR AND _RUNTIME_JAR_SRC)
    add_custom_command(TARGET topo-build-jvm-java POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${_RUNTIME_JAR_SRC}
            $<TARGET_FILE_DIR:topo-build-jvm-java>/topo-runtime.jar
        COMMENT "Deploying topo-runtime.jar next to topo-build-jvm-java"
    )
endif()
