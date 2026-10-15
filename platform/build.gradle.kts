import java.math.BigDecimal

plugins {
    id("mazewall.quality-conventions")
    id("info.solidsoft.pitest")
    alias(libs.plugins.plantuml)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

kotlin {
    jvmToolchain(25)
    explicitApi()
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
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

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named("integrationTestRuntimeOnly") {
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

tasks.test {
    dependsOn(compileCAbiOracle)
}

val plantumlConfig by configurations.creating

dependencies {
    plantumlConfig(libs.plantuml.core)
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

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(
        setOf(
            "io.mazewall.platform.syscall.SeccompAction*",
            "io.mazewall.platform.daemon.UnixListenDaemonMachine*",
            "io.mazewall.ffi.networking.SeccompConnectionMachine*",
            "io.mazewall.platform.syscall.PrctlCommand*",
            "io.mazewall.platform.syscall.NetworkSyscallMapper*",
        ),
    )
    excludedClasses.set(
        setOf(
            "io.mazewall.ffi.nix.*",
            "io.mazewall.ffi.linux.*",
            "io.mazewall.MockNativeEngine*",
            // ACT_KILL_THREAD's native code is the kernel's zero value, so PIT's
            // "replace return with zero" mutant is observationally equivalent.
            "io.mazewall.platform.syscall.SeccompAction${'$'}ACT_KILL_THREAD",
        ),
    )
    // Kotlin inserts this non-null check for AtomicReference.get(); stateRef's
    // generic contract makes a null result impossible, so removing it is also
    // observationally equivalent.
    excludedMethods.set(setOf("apply\\${'$'}io_mazewall_platform"))
    targetTests.set(
        setOf(
            "io.mazewall.platform.lifecycle.SeccompActionTest",
            "io.mazewall.platform.daemon.UnixListenDaemonMachineTest",
            "io.mazewall.ffi.networking.SeccompConnectionMachineTest",
            "io.mazewall.platform.lifecycle.PrctlCommandTest",
            "io.mazewall.platform.lifecycle.NetworkSyscallMapperTest",
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

classDiagrams {
    plantumlServer = null
    renderClasspath(plantumlConfig)
    defaults {
        style {
            theme("spacelab")
            hide("empty members")
        }
        exclude(methods().withNameLike("component*") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("copy") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(classes().withNameLike("*\\\$*"))
        exclude(classes().withNameLike("*Kt"))
        exclude(classes().withName("io.mazewall.platform.syscall.Arch"))
        exclude(classes().withName("io.mazewall.platform.syscall.Syscall"))
        exclude(classes().withName("io.mazewall.ffi.NativeConstants"))
        exclude(classes().withName("io.mazewall.ffi.Layouts"))
    }

    diagram {
        name("Platform Class Diagram")
        include(packages().withName("io.mazewall.platform"))
        include(packages().withName("io.mazewall.ffi"))
        writeTo(file("$rootDir/docs/diagrams/platform_class_diagram.puml"))
        renderTo(file("$rootDir/docs/diagrams/platform_class_diagram.svg"))
    }
}

tasks.named("generateClassDiagrams") {
    val pumlFile = file("$rootDir/docs/diagrams/platform_class_diagram.puml")
    val svgFile = file("$rootDir/docs/diagrams/platform_class_diagram.svg")
    doLast {
        fun cleanup(file: File) {
            if (!file.exists()) return
            var content = file.readText()
            content = content.replace(Regex("([a-zA-Z0-9_]+)-[a-zA-Z0-9_-]{7,15}(?=\\b|\\(|$)"), "$1")
            content = content.replace(Regex("\\\$[a-zA-Z0-9_]+"), "")
            content = content.replace("set separator none\n", "")
            content = content.replace(Regex(" data=\"([^\"]*)\" id=\"([^\"]*)\" data=\"([^\"]*)\""), " data-name=\"$1\" id=\"$2\" data-line=\"$3\"")
            file.writeText(content)
        }
        cleanup(pumlFile)
        cleanup(svgFile)
    }
}
