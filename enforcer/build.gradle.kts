import java.math.BigDecimal

plugins {
    kotlin("jvm")
    id("info.solidsoft.pitest")
    alias(libs.plugins.plantuml)
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.bcv)
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

val compileVulnerableRop = tasks.register<Exec>("compileVulnerableRop") {
    group = "build"
    description = "Compiles the vulnerable C library for CET testing"
    commandLine("bash", "${rootDir}/scripts/run_cet_demo.sh")
}

val integrationTestJvmArgs =
    listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx256m",
        "-Xms128m",
        "-Dfile.encoding=UTF-8",
        "-Dsun.jnu.encoding=UTF-8",
    ) + (
        if (System.getProperty("debug.disableSelfVerify") == "true") listOf("-Ddebug.disableSelfVerify=true")
        else emptyList()
    ) + (
        if (System.getProperty("debug.enableSelfVerify") == "true") listOf("-Dio.mazewall.selfVerify=true")
        else emptyList()
    )


// issue-20260823-172001: CLI --tests filters that match only 'needs-fresh-jvm' classes fail on
// :enforcer:integrationTest with "No tests found", silently hiding the correct task. Emit a
// routing hint at CONFIGURATION time (Gradle 9 does not expose CLI patterns to Test tasks).
val cliTestFilterArgs = gradle.startParameter.taskRequests
    .flatMap { it.args }
fun extractCliTestPatterns(args: List<String>): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a.startsWith("--tests=") -> out += a.substringAfter("=")
            a == "--tests" && i + 1 < args.size -> { out += args[i + 1]; i++ }
        }
        i++
    }
    return out.map { it.trim('*', ' ') }.filter { it.isNotBlank() }
}
val cliTestPatterns = extractCliTestPatterns(cliTestFilterArgs)

fun findMatchingTaggedClasses(patterns: List<String>, wantTag: Boolean): List<String> {
    if (patterns.isEmpty()) return emptyList()
    val hits = mutableListOf<String>()
    sourceSets["integrationTest"].output.classesDirs.forEach { dir ->
        if (!dir.isDirectory) return@forEach
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .filter { cls ->
                val simple = cls.nameWithoutExtension.substringAfterLast('/')
                patterns.any { pat -> simple.contains(pat.substringAfterLast('.'), ignoreCase = true) }
            }
            .forEach { cls ->
                val bytes = String(cls.readBytes(), Charsets.ISO_8859_1)
                val tagged = bytes.contains("NeedsFreshJvm")
                if (tagged == wantTag) {
                    hits += cls.nameWithoutExtension.substringAfterLast('/')
                }
            }
    }
    return hits.distinct()
}

if (cliTestPatterns.isNotEmpty()) {
    val freshOnly = findMatchingTaggedClasses(cliTestPatterns, wantTag = true)
    val plain = findMatchingTaggedClasses(cliTestPatterns, wantTag = false)
    if (plain.isEmpty() && freshOnly.isNotEmpty()) {
        logger.warn(
            "[FILTER HINT] ${freshOnly.joinToString()} are tagged 'needs-fresh-jvm' and will NOT run on " +
                ":enforcer:integrationTest. Use: ./gradlew :enforcer:integrationTestFreshJvm --tests <pattern>",
        )
    }
}

fun Test.configureIntegrationHarness() {
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    jvmArgs(integrationTestJvmArgs)
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
    testLogging {
        showStandardStreams = true
    }
    dependsOn(compileVulnerableRop)
    // issue-20260823-172001: routing hints are emitted at CONFIGURATION time (see
    // cliTestPatterns logic above) because CLI --tests patterns are not visible here.
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        configureIntegrationHarness()
        description = "Kernel tests that do not install on the JUnit worker JVM"
        useJUnitPlatform {
            excludeTags("needs-fresh-jvm")
        }
        forkEvery = 0
        maxParallelForks = 1
    }

val integrationTestFreshJvm =
    tasks.register<Test>("integrationTestFreshJvm") {
        configureIntegrationHarness()
        description = "Kernel tests that install seccomp/USER_NOTIF on the worker JVM"
        useJUnitPlatform {
            includeTags("needs-fresh-jvm")
        }
        forkEvery = 1
    }

tasks.check {
    dependsOn(integrationTest, integrationTestFreshJvm)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx256m", "-Xms128m", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
}

val plantumlConfig by configurations.creating

dependencies {
    plantumlConfig(libs.plantuml.core)
    api(project(":platform"))
    compileOnly(libs.kotlinxCoroutines)
    implementation(libs.kotlinxSerialization)
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
    mainClass.set("io.mazewall.enforcer.engine.JvmFloorWorkload")
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
            "io.mazewall.enforcer.engine.FilterInstallationPlanner*",
            "io.mazewall.enforcer.PolicyCombining*",
            "io.mazewall.enforcer.supervisor.SupervisorNotificationMachine*",
            "io.mazewall.enforcer.state.ContainmentStateRegistry*",
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
            "io.mazewall.enforcer.supervisor.SupervisorNotificationMachineTest",
            "io.mazewall.enforcer.ContainmentStateRegistryTest",
        ),
    )

    jvmArgs.set(listOf("--enable-native-access=ALL-UNNAMED"))
    timeoutConstInMillis.set(2000)
    timeoutFactor.set(BigDecimal.valueOf(1.25))
    threads.set(System.getProperty("pitest.threads")?.toInt() ?: 4)
}

classDiagrams {
    plantumlServer = null
    renderClasspath(plantumlConfig)
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
        renderTo(file("$rootDir/docs/diagrams/enforcer_class_diagram.svg"))
    }
}

tasks.named("generateClassDiagrams") {
    val pumlFile = file("$rootDir/docs/diagrams/enforcer_class_diagram.puml")
    val svgFile = file("$rootDir/docs/diagrams/enforcer_class_diagram.svg")

    doLast {
        fun cleanup(file: File) {
            if (file.exists()) {
                var content = file.readText()
                // Strip Kotlin value-class mangling hash suffix (e.g. -r9EpL9Y, -LsA-840, etc.)
                content = content.replace(Regex("([a-zA-Z0-9_]+)-[a-zA-Z0-9_-]{7,15}(?=\\b|\\(|$)"), "$1")
                // Strip any leftover internal module access flags (e.g. $io_mazewall_enforcer)
                content = content.replace(Regex("\\\$[a-zA-Z0-9_]+"), "")
                // Fix PlantUML SVG XML parsing error (duplicate data attribute in <g class="entity">)
                content = content.replace(Regex(" data=\"([^\"]*)\" id=\"([^\"]*)\" data=\"([^\"]*)\""), " data-name=\"$1\" id=\"$2\" data-line=\"$3\"")
                file.writeText(content)
            }
        }

        cleanup(pumlFile)
        cleanup(svgFile)
    }
}

tasks.named("build") {
    if (System.getenv("CI") != "true" && System.getenv("MAZEWALL_IN_CONTAINER") != "true") {
        dependsOn("generateClassDiagrams")
    }
}

