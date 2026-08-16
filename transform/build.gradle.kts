plugins {
    java
    application
}

group = "dev.topo"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Deterministic source encoding: javac defaults to the PLATFORM encoding,
// which is windows-1252 on Windows runners — the UTF-8 arrows/em-dashes in
// the pass sources (e.g. LoopVectorizePass.java "FALOAD ← IALOAD") fail to
// compile there ("unmappable character (0x90)"). Pin UTF-8 for every
// JavaCompile task (compileJava AND compileTestJava).
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

dependencies {
    // ASM 9.8 is the first release that reads Java 25 (class major 69)
    // bytecode — topo-runtime.jar is compiled by a JDK-25 toolchain and
    // PostTransformVerifier scans it; an older ASM that cannot read class
    // major 69 fails the equivalence build.
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.topo.transform.TopoTransformer")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("topo-transform")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "dev.topo.transform.TopoTransformer"
    }
    // Fat jar: include all dependencies
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
