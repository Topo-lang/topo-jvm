# topo-jvm -- JVM backend for the Topo toolchain

JVM backend ecosystem for Topo. Zero LLVM dependency. Ships:

- **topo-build-jvm-java**: C++ executable invoked by `topo-build` as the
  JVM-side backend tool. Reads a JSON request, drives `javac`, runs the
  bytecode transformer, and packages the output JAR.
- **topo-transform.jar**: Gradle-built ASM bytecode transformer with 15
  passes (Visibility, StaticPromotion, InlineHint, ReturnSpecialization,
  DataLayout, TypeNarrowing, Parallel, LoopVectorize, Pipeline, Prefetch,
  Observability, Adaptive, Arena, StageReorder, Obfuscation).
- **topo-decompile-jvm.jar**: JVMLifter — Java bytecode → TranspileModel
  (reverse path for decompile-then-transpile workflows).

## Structure

| Directory | Purpose |
|-----------|---------|
| `tools/topo-build-jvm-java/` | C++ build-tool entrypoint (executes javac + transform + JAR packaging) |
| `transform/` | Gradle Java project; builds `topo-transform.jar` (ASM bytecode rewriter) |
| `decompile/` | Gradle Java project; builds `topo-decompile-jvm-1.0.0.jar` (JVMLifter) |
| `test/unit/` | Standalone C++ unit tests for the build tool's input-trust validation |
| `test/e2e/` | Deferred (depends on `topo-build` CLI; lives in topo-cli) |
| `benchmarks/` | Per-feature Java benchmark projects (built on demand by e2e fixtures) |

## Upstream packages

- `topo-core` (built with `TOPO_CORE_WITH_LANG=ON`)
- `topo-lang`
- `topo-lang-java` (provides `topo::lang-java::TopoJavaDriver`, which
  `topo-build-jvm-java` reverse-links — same pattern as topo-llvm's
  reverse-link onto `topo::lang-cpp::TopoCppDriver`)

## Build

```bash
cmake -S . -B build -G Ninja \
    -DCMAKE_PREFIX_PATH=<topo-install-prefix> \
    -DCMAKE_TOOLCHAIN_FILE=$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake
cmake --build build
ctest --test-dir build --output-on-failure
```

### Gradle requirements

The `transform/` and `decompile/` subprojects build their JARs via
Gradle. Each subproject ships its own `gradlew` wrapper (Gradle 9.5.1);
the build picks it up automatically. A system-wide `gradle` binary on
`PATH` is honored first if present. Either way, a usable JDK is
required:

- Auto-resolved from `TOPO_GRADLE_JAVA_HOME`, then `JAVA_HOME`.
- If neither resolves, Gradle falls back to its own resolution.

Set `-DTOPO_JVM_BUILD_TRANSFORM=OFF` or `-DTOPO_JVM_BUILD_DECOMPILE=OFF`
to skip the corresponding Gradle subproject. The build auto-disables
either gate when neither system gradle nor the bundled wrapper is
reachable.

### Build options

| Option | Default | Purpose |
|--------|---------|---------|
| `TOPO_JVM_BUILD_TOOLS` | `ON` | Build `topo-build-jvm-java` (needs `topo-lang-java`) |
| `TOPO_JVM_BUILD_TRANSFORM` | `ON` (auto-off if no gradle/wrapper) | Build the ASM transformer JAR |
| `TOPO_JVM_BUILD_DECOMPILE` | `ON` (auto-off if no gradle/wrapper) | Build the JVMLifter JAR |
| `TOPO_JVM_BUILD_TESTS` | `ON` | Build unit tests (gates on `TOPO_JVM_BUILD_TOOLS`) |
| `TOPO_GRADLE_JAVA_HOME` | unset | Override JDK for Gradle invocations |

## Install

```bash
cmake --install build --prefix /usr/local
```

Installs:

- `bin/topo-build-jvm-java` (when `TOPO_JVM_BUILD_TOOLS=ON`)
- `share/topo-jvm/lib/topo-transform.jar` (when `TOPO_JVM_BUILD_TRANSFORM=ON`)
- `share/topo-jvm/lib/topo-decompile-jvm-1.0.0.jar` (when `TOPO_JVM_BUILD_DECOMPILE=ON`)
- `lib/cmake/topo-jvm/topo-jvm{Config,Targets}.cmake` (find_package data)

## Use from downstream CMake

```cmake
find_package(topo-jvm CONFIG REQUIRED)
# Imported target:  topo::jvm::topo-build-jvm-java   (executable)
# Cache variable :  TOPO_JVM_LIBDIR  (path to JARs)
```

## License

MIT — see `LICENSE`.
