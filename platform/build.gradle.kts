import java.math.BigDecimal

plugins {
    kotlin("jvm")
    id("info.solidsoft.pitest")
}

kotlin {
    jvmToolchain(25)
}

sourceSets {
    create("integrationTest") {
        compileClasspath += main.get().output + test.get().output
        runtimeClasspath += main.get().output + test.get().output
    }
}

val kotlinExtension = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
val kotlinCompilations = kotlinExtension.target.compilations
kotlinCompilations.named("integrationTest") {
    associateWith(kotlinCompilations.getByName("main"))
    associateWith(kotlinCompilations.getByName("test"))
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        group = "verification"
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        forkEvery = 1
    }

val cAbiOracle = layout.buildDirectory.file("abi-oracle/layout-oracle")
val cAbiOracleDirectory = layout.buildDirectory.dir("abi-oracle")

val compileCAbiOracle =
    tasks.register<Exec>("compileCAbiOracle") {
        group = "verification"
        description = "Compiles the host C-header ABI oracle used by Layouts tests."
        inputs.file(layout.projectDirectory.file("src/test/resources/abi/layout_oracle.c"))
        outputs.file(cAbiOracle)
        commandLine(
            "/bin/sh",
            "-c",
            "mkdir -p \"$1\" && exec cc -std=c11 -Wall -Werror \"$2\" -o \"$3\"",
            "compileCAbiOracle",
            cAbiOracleDirectory.get().asFile.absolutePath,
            layout.projectDirectory
                .file("src/test/resources/abi/layout_oracle.c")
                .asFile.absolutePath,
            cAbiOracle.get().asFile.absolutePath,
        )
    }

tasks.register<Exec>("verifyCAbi") {
    group = "verification"
    description = "Runs the mandatory C-header ABI oracle."
    dependsOn(compileCAbiOracle)
    commandLine(cAbiOracle.get().asFile.absolutePath)
}

tasks.check {
    dependsOn(integrationTest)
}

tasks.test {
    dependsOn(compileCAbiOracle)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(rootProject.sourceSets["sharedTest"].output)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotlinxCoroutines)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.slf4j.nop)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(
        setOf(
            "io.mazewall.core.SeccompAction*",
            "io.mazewall.platform.daemon.UnixListenDaemonMachine*",
            "io.mazewall.ffi.networking.SeccompConnectionMachine*",
            "io.mazewall.core.PrctlCommand*",
            "io.mazewall.core.NetworkSyscallMapper*",
        ),
    )
    excludedClasses.set(
        setOf(
            "io.mazewall.ffi.nix.*",
            "io.mazewall.ffi.linux.*",
            "io.mazewall.MockNativeEngine*",
        ),
    )
    targetTests.set(
        setOf(
            "io.mazewall.core.SeccompActionTest",
            "io.mazewall.platform.daemon.UnixListenDaemonMachineTest",
            "io.mazewall.ffi.networking.SeccompConnectionMachineTest",
            "io.mazewall.core.PrctlCommandTest",
            "io.mazewall.core.NetworkSyscallMapperTest",
        ),
    )
    jvmArgs.set(listOf("--enable-native-access=ALL-UNNAMED"))
    timeoutConstInMillis.set(2000)
    timeoutFactor.set(BigDecimal.valueOf(1.25))
    threads.set(System.getProperty("pitest.threads")?.toInt() ?: 4)

    // Host-unit floor for pure ABI values and protocol state machines.
    coverageThreshold.set(97)
    mutationThreshold.set(61)
    testStrengthThreshold.set(98)
}
