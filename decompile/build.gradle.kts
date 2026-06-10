plugins {
    java
    application
}

group = "dev.topo"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // COUPLED to topo-build-jvm-java's validateTargetVersion cap (main.cpp):
    // the {8,11,17,21} cap is what keeps user bytecode within what this ASM
    // reads. Raising the cap toward JDK 22+/25 → re-check/bump ASM here in
    // the same change (transform/ already carries 9.8 for the v69 runtime jar).
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-tree:9.7.1")
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
