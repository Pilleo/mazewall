plugins {
    kotlin("jvm")
    id("info.solidsoft.pitest")
    alias(libs.plugins.plantuml)
}

kotlin {
    jvmToolchain(25)
}

sourceSets {
    test {
        java.srcDir(rootProject.file("src/sharedTest/kotlin"))
    }
    create("integrationTest") {
        java.srcDir(rootProject.file("src/sharedTest/kotlin"))
        compileClasspath += main.get().output + test.get().output
        runtimeClasspath += main.get().output + test.get().output
    }
}

// Associate integration tests with main and test to allow accessing internal members and test utilities
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
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
        forkEvery = 1
        testLogging {
            showStandardStreams = true
        }
    }

tasks.check {
    dependsOn(integrationTest)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.archunit.junit5)
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

tasks.register<JavaExec>("runScratch") {
    group = "application"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.mazewall.Scratch")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("runJvmFloor") {
    group = "verification"
    description = "Runs the synthetic JVM floor workload to exercise JIT, GC, Loom, and NIO subsystems."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.mazewall.enforcer.JvmFloorWorkload")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

pitest {
    junit5PluginVersion.set("1.2.1")

    // Core security logic must have high mutation coverage
    targetClasses.set(
        setOf(
            "io.mazewall.Policy*",
            "io.mazewall.BpfFilter*",
            "io.mazewall.SbobParser*",
            "io.mazewall.enforcer.FilterInstallationPlanner*",
            "io.mazewall.enforcer.PolicyCombining*",
        ),
    )

    // Exclude slow/fragile kernel tests and native bridges
    excludedClasses.set(
        setOf(
            "io.mazewall.LinuxNative*",
            "io.mazewall.RealNativeEngine*",
            "io.mazewall.IsolatedProcessTester*",
            "io.mazewall.MockNativeEngine*",
        ),
    )

    // Only run unit tests (fast, no kernel interaction)
    // EXCLUDE property-based tests (like PolicyCombinePropertyTest) from mutation testing.
    // PBTs run hundreds of iterations per mutant, causing combinatorial explosion and minion timeouts.
    targetTests.set(
        setOf(
            "io.mazewall.PolicyTest",
            "io.mazewall.seccomp.BpfFilterTest",
            "io.mazewall.SbobParserTest",
            "io.mazewall.enforcer.FilterInstallationPlannerTest",
        ),
    )

    jvmArgs.set(listOf("--enable-native-access=ALL-UNNAMED"))

    threads.set(System.getProperty("pitest.threads")?.toInt() ?: 4)
}

classDiagrams {
    defaults {
        style {
            hidePackages()
            theme("spacelab")
            hide("empty members")
        }
        exclude(methods().withNameLike("component*") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("copy") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("getEntries") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("buildFromActions\$io_mazewall_enforcer") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("combineProcessWide") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("combine") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(classes().withNameLike("*\\$*"))
        // Exclude constant/data-heavy/mapper noise
        exclude(classes().withName("io.mazewall.core.Arch"))
        exclude(classes().withName("io.mazewall.core.Syscall"))
        exclude(classes().withNameLike("io.mazewall.core.*Mapper"))
        exclude(classes().withName("io.mazewall.ffi.NativeConstants"))
        exclude(classes().withName("io.mazewall.ffi.Layouts"))
    }

    diagram {
        name("Enforcer Class Diagram")
        include(packages().withName("io.mazewall"))
        writeTo(file("$rootDir/docs/diagrams/enforcer_class_diagram.puml"))
        insertInto(file("$rootDir/docs/internals/enforcer_architecture.md"))
    }
}

tasks.named("generateClassDiagrams") {
    doLast {
        val pumlFile = file("$rootDir/docs/diagrams/enforcer_class_diagram.puml")
        if (pumlFile.exists()) {
            var content = pumlFile.readText()
            // Strip Kotlin value-class mangling hash suffix (e.g. -r9EpL9Y, -LsA-840, etc.)
            content = content.replace(Regex("([a-zA-Z0-9_]+)-[a-zA-Z0-9_-]{7,15}(?=\\b|\\(|$)"), "$1")
            // Strip any leftover internal module access flags (e.g. $io_mazewall_enforcer)
            content = content.replace(Regex("\\\$[a-zA-Z0-9_]+"), "")
            pumlFile.writeText(content)
        }
        val mdFile = file("$rootDir/docs/internals/enforcer_architecture.md")
        if (mdFile.exists()) {
            var content = mdFile.readText()
            content = content.replace(Regex("([a-zA-Z0-9_]+)-[a-zA-Z0-9_-]{7,15}(?=\\b|\\(|$)"), "$1")
            content = content.replace(Regex("\\\$[a-zA-Z0-9_]+"), "")
            mdFile.writeText(content)
        }
    }
}


