plugins {
    java
    application
}

group = "dev.topo"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Deterministic source encoding: javac defaults to the PLATFORM encoding,
// which is windows-1252 on Windows runners — the UTF-8 arrows/em-dashes in
// the pass sources fail to compile there ("unmappable character (0x90)").
// Pin UTF-8 for every JavaCompile task (compileJava AND compileTestJava).
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

dependencies {
    // ASM 9.8 reads Java 25 class files (major 69), matching the default
    // target emitted by topo-build-jvm-java and the transformer.
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("topo.decompile.Main")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "topo.decompile.Main"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
