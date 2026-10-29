import io.mazewall.build.TriageArguments
import io.mazewall.build.VerifyDependencyState
import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension
import org.owasp.dependencycheck.gradle.tasks.ConfiguredTask
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/** Demos and tools keep their existing explicit analyzer selection. */
fun Project.skipsKotlinStyleTools(): Boolean = path.startsWith(":demos") || path.startsWith(":tools")

plugins {
    id("mazewall.quality-conventions")
    id("io.mazewall.spotbugs-toolchain")
    id("mazewall.publishing-conventions")
    alias(libs.plugins.dependencyCheck)
    alias(libs.plugins.pitest) apply false
    id("base")
    id("io.mazewall.test-health")
}

allprojects {
    group = "io.mazewall"
    version = "0.1.0-SNAPSHOT"

    if (skipsKotlinStyleTools()) {
        tasks.configureEach {
            if (name.contains("detekt", ignoreCase = true) || name.contains("ktlint", ignoreCase = true)) {
                enabled = false
            }
        }
    }

    // Gradle 9 ships an older JaCoCo agent that cannot instrument JDK 25
    // classes. Configure the catalogued agent for every project, including
    // demos, so a coverage run never silently loses JVM classes.
    plugins.withId("jacoco") {
        extensions.configure<JacocoPluginExtension> {
            toolVersion =
                rootProject.extensions
                    .getByType<VersionCatalogsExtension>()
                    .named("libs")
                    .findVersion("jacoco")
                    .get()
                    .requiredVersion
        }
    }

    if (!skipsKotlinStyleTools()) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.7.0")
            verbose.set(true)
            outputToConsole.set(true)
            coloredOutput.set(true)
            reporters {
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
            }
        }
    }

    // JitPack Shim: Satisfy JitPack's broken 'listDeps' task by injecting
    // the missing 'configurations' property into the task instance.
    tasks.matching { it.name == "listDeps" }.configureEach {
        // Using extensions/extra to satisfy Groovy property resolution
        (this as? ExtensionAware)?.extra?.set("configurations", project.configurations)
    }

    // Aggressively skip tests on JitPack because the host kernel (4.4)
    // is too old for Seccomp/Landlock/FFM and will cause failures.
    if (System.getenv("JITPACK") == "true") {
        tasks.withType<Test>().configureEach {
            enabled = false
        }
    }

    val isVerbose = gradle.startParameter.logLevel in listOf(LogLevel.INFO, LogLevel.DEBUG)

    tasks.withType<Test>().configureEach {
        if (project.path in setOf(":enforcer", ":platform", ":profiler")) {
            useJUnitPlatform()
            jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "-Xmx256m",
                "-Xms128m",
                "-Dfile.encoding=UTF-8",
                "-Dsun.jnu.encoding=UTF-8",
            )
            environment("LC_ALL", "C.UTF-8")
            environment("LANG", "C.UTF-8")
            systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
        }

        // Child daemons can be intentionally chatty. Keep diagnostics bounded so a
        // stalled/failing test cannot exhaust the Gradle daemon while we retain output.
        val failedTestsOutputs = ConcurrentHashMap<String, StringBuilder>()
        val maxCapturedOutputChars = 64 * 1024

        if (isVerbose || name == "presentationTest") {
            testLogging {
                events("passed", "skipped", "failed", "standardOut", "standardError")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
            }
        } else {
            // Keep normal CI logs focused on the actionable failure. Full traces and
            // all test output remain available with --info or --debug.
            testLogging {
                events = setOf(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
                showExceptions = true
                showCauses = true
                showStackTraces = false
                showStandardStreams = false
            }

            addTestOutputListener { descriptor, event ->
                val testId = "${descriptor.className ?: "UnknownClass"}.${descriptor.name}"
                val buffer = failedTestsOutputs.getOrPut(testId) { StringBuilder() }
                synchronized(buffer) {
                    val remaining = maxCapturedOutputChars - buffer.length
                    if (remaining > 0) buffer.append(event.message.take(remaining))
                }
            }

            addTestListener(
                object : TestListener {
                    override fun beforeSuite(suite: TestDescriptor) {}

                    override fun afterSuite(
                        suite: TestDescriptor,
                        result: TestResult,
                    ) {}

                    override fun beforeTest(testDescriptor: TestDescriptor) {}

                    override fun afterTest(
                        testDescriptor: TestDescriptor,
                        result: TestResult,
                    ) {
                        val testId = "${testDescriptor.className ?: "UnknownClass"}.${testDescriptor.name}"
                        if (result.resultType == TestResult.ResultType.FAILURE) {
                            println("\n========== TEST FAILED ==========")
                            println("$testId")
                            println("=================================")
                            val output = failedTestsOutputs[testId]?.toString()
                            if (!output.isNullOrBlank()) {
                                println("Captured stdout/stderr:")
                                println(output)
                                println("=================================\n")
                            }
                        }
                        failedTestsOutputs.remove(testId)
                    }
                },
            )
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
            freeCompilerArgs.add("-Xcontext-parameters")
            freeCompilerArgs.add("-opt-in=io.mazewall.platform.lifecycle.MazewallInternal")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(22)
    }
}

val checkGradleLazyResolution =
    tasks.register<io.mazewall.build.CheckGradleLazyResolution>("checkGradleLazyResolution") {
        group = "verification"
        description = "Reject eager Gradle configuration resolution in Kotlin build scripts"
        checkoutPath.set(layout.projectDirectory.asFile.absolutePath)
    }

tasks.named("check") {
    dependsOn(checkGradleLazyResolution)
}

tasks.register<io.mazewall.build.CheckCiCachePolicy>("checkCiCachePolicy") {
    group = "verification"
    description = "Verifies host/container and mutable dependency-data cache ownership in CI."
    workflow.set(layout.projectDirectory.file(".github/workflows/ci.yml"))
}

tasks.named("check") { dependsOn("checkCiCachePolicy") }

tasks.register("format") {
    group = "formatting"
    description = "Formats Kotlin sources in projects that opt into KtLint."
    dependsOn("ktlintFormat")
    dependsOn(
        listOf(":platform", ":enforcer", ":profiler", ":portals:runtime", ":portals:codegen", ":portals:worker")
            .map { "$it:ktlintFormat" },
    )
}
dependencies {
    testImplementation(kotlin("test"))
}

sourceSets {
    create("sharedTest") {
        kotlin.srcDir("src/sharedTest/kotlin")
        resources.srcDir("src/sharedTest/resources")
    }
}

// Shared test fixtures deliberately model native and platform failure paths.
// They are repository-only test code, so grant them the same narrow access as
// the direct implementation modules—never a published consumer.
val rootKotlinExtension = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
val rootKotlinCompilations = rootKotlinExtension.target.compilations
val rootPlatformJar = objects.fileProperty()
val rootEnforcerJar = objects.fileProperty()
gradle.projectsEvaluated {
    rootPlatformJar.set(project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile })
    rootEnforcerJar.set(project(":enforcer").tasks.named<Jar>("jar").flatMap { it.archiveFile })
}
configurations.named("sharedTestCompileClasspath") {
    attributes.attribute(
        org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
        objects.named(org.gradle.api.attributes.LibraryElements.JAR),
    )
}
rootKotlinCompilations.matching { it.name == "sharedTest" }.configureEach {
    compileTaskProvider.configure {
        dependsOn(":platform:jar", ":enforcer:jar")
        compilerOptions.freeCompilerArgs.addAll(
            rootPlatformJar.zip(rootEnforcerJar) { platform, enforcer ->
                listOf(
                    "-Xfriend-paths=${platform.asFile.absolutePath}",
                    "-Xfriend-paths=${enforcer.asFile.absolutePath}",
                )
            },
        )
    }
}

tasks.named<ProcessResources>("processSharedTestResources") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.matching { it.name.startsWith("spotbugsTest") || it.name.startsWith("spotbugsIntegrationTest") || it.name.startsWith("spotbugsSharedTest") }.configureEach {
    enabled = false
}
dependencies {
    "sharedTestImplementation"(project(":enforcer"))
    "sharedTestImplementation"(project(":platform"))
    "sharedTestImplementation"(libs.archunit.junit5)
    "sharedTestImplementation"(libs.junit.jupiter.api)
    "sharedTestImplementation"(libs.junit.platform.launcher)
    testImplementation(sourceSets["sharedTest"].output)
    testImplementation(libs.archunit.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation("org.junit.platform:junit-platform-launcher:1.10.2")
    testCompileOnly(project(":platform")) // Resolve the shared MazewallInternal compiler opt-in.
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Aggregate architecture verification only; production module dependencies remain unchanged.
    listOf("enforcer", "platform", "profiler", "portals:runtime", "portals:worker", "portals:codegen").forEach { module ->
        testRuntimeOnly(project(":$module"))
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val consumerVerification =
    sourceSets.create("consumerVerification") {
        kotlin.srcDir("src/consumerVerification/kotlin")
        compileClasspath += configurations.testRuntimeClasspath.get() + sourceSets["sharedTest"].output
        runtimeClasspath += output + compileClasspath
    }

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsConsumerVerification") {
    // JUnit's compile-only API annotations are absent from the consumer harness's runtime-derived classpath.
    auxClassPaths.from(configurations.testCompileClasspath)
}

tasks.register<Test>("consumerHarnessTest") {
    group = "verification"
    description = "Checks portable consumer harness ownership without requiring published applications."
    testClassesDirs = consumerVerification.output.classesDirs
    classpath = consumerVerification.runtimeClasspath
    useJUnitPlatform { excludeTags("packaged-consumer", "consumer-bootstrap", "consumer-native", "boundary-evidence", "optional-integration") }
}

tasks.register<Test>("consumerCheck") {
    group = "verification"
    description = "Runs the independent real-publication JDK22/JDK25 consumer matrix."
    testClassesDirs = consumerVerification.output.classesDirs
    classpath = consumerVerification.runtimeClasspath
    useJUnitPlatform { includeTags("packaged-consumer") }
    systemProperty("consumerWorkspace", layout.projectDirectory.asFile.absolutePath)
    for (name in listOf("consumerRepository", "consumerVersion", "consumerKotlinVersion", "consumerEvidence", "consumerWriteLocks", "verificationLock")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.matching { it.name == "unitCheck" }.configureEach { dependsOn("consumerHarnessTest") }

val pitchMeasurementSelfTest =
    tasks.register<Exec>("pitchMeasurementSelfTest") {
        group = "verification"
        description = "Checks bounded application measurement accounting and corruption controls without native setup."
        commandLine("python3", "-m", "unittest", "discover", "-s", "benchmarks/integration-boundaries/pitch", "-p", "test_*.py")
    }
tasks.named("check") { dependsOn(pitchMeasurementSelfTest) }
tasks.matching { it.name == "unitCheck" }.configureEach { dependsOn(pitchMeasurementSelfTest) }

tasks.register<Test>("verifyIntegrationBoundaryEvidence") {
    group = "verification"
    description = "Validates bounded boundary observations and corruption controls; custody authentication remains separate."
    testClassesDirs = consumerVerification.output.classesDirs
    classpath = consumerVerification.runtimeClasspath
    useJUnitPlatform { includeTags("boundary-evidence") }
    for (name in listOf("boundaryRaw", "boundaryCustody")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.register<Test>("consumerBootstrapCheck") {
    group = "verification"
    description = "Exercises real Portal producer freshness and packaging in a disposable checkout."
    testClassesDirs = consumerVerification.output.classesDirs
    classpath = consumerVerification.runtimeClasspath
    useJUnitPlatform { includeTags("consumer-bootstrap") }
    systemProperty("consumerWorkspace", layout.projectDirectory.asFile.absolutePath)
    providers.gradleProperty("verificationLock").orNull?.let { systemProperty("verificationLock", it) }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.register<Test>("consumerNativeCheck") {
    group = "verification"
    description = "Checks finished publication bytes, external fixture review and required native application effects."
    testClassesDirs = consumerVerification.output.classesDirs
    classpath = consumerVerification.runtimeClasspath
    useJUnitPlatform { includeTags("consumer-native") }
    maxParallelForks = 1
    systemProperty("consumerWorkspace", layout.projectDirectory.asFile.absolutePath)
    for (name in listOf("verificationLock", "consumerRepository", "consumerVersion", "consumerRevision", "consumerKoogJar", "consumerBootJar", "consumerEvidence")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.register<Test>("optionalIntegrationCheck") {
    group = "verification"
    description = "Stages exact optional publications and verifies real Koog, JDBC/Security and finished Boot consumers."
    testClassesDirs = consumerVerification.output.classesDirs
    classpath = consumerVerification.runtimeClasspath
    useJUnitPlatform { includeTags("optional-integration") }
    maxParallelForks = 1
    systemProperty("consumerWorkspace", layout.projectDirectory.asFile.absolutePath)
    providers.gradleProperty("verificationLock").orNull?.let { systemProperty("verificationLock", it) }
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    format = "ALL"
    suppressionFile = "$rootDir/config/dependency-check/suppressions.xml"
    data.directory = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let { "$it/dependency-check-data" } ?: "$rootDir/.gradle/dependency-check-data"
    System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }?.let {
        nvd.apiKey = it
    }
    // Disable OSS Index as it requires separate credentials and is currently failing in CI
    analyzers {
        ossIndex.enabled = false
    }
    // Skip checking demo projects since they are deliberately vulnerable
    skipProjects = listOf(":demos:cli-demo", ":demos:vulnerable-web-app")
    // Only scan production compile and runtime configurations to avoid scanning build tooling like detekt and ktlint
    scanConfigurations = listOf("compileClasspath", "runtimeClasspath")
}

// 13.0.0 task defaults otherwise override the extension's data-directory convention.
tasks.withType<ConfiguredTask>().configureEach {
    val extension = project.extensions.getByType<DependencyCheckExtension>()
    data.directory.set(extension.data.directory)
}

tasks.named("dependencyCheckAnalyze").configure {
    onlyIf("NVD API Key is required for CI performance") {
        System.getenv("CI") != "true" || !System.getenv("NVD_API_KEY").isNullOrBlank()
    }
}

detekt {
    buildUponDefaultConfig = false
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files("src/main/kotlin"))
    failOnSeverity = dev.detekt.gradle.extensions.FailOnSeverity.Error
}

subprojects {
    plugins.withId("java") {
        if (path in setOf(":demos:cli-demo", ":demos:agent-sandbox-demo", ":demos:vulnerable-web-app", ":demos:koog-contained-tool")) {
            val demoTests = extensions.getByType<SourceSetContainer>()["test"]
            tasks.named<Test>("test") { useJUnitPlatform { excludeTags("presentation") } }
            tasks.register<Test>("presentationTest") {
                group = "verification"
                description = "Run independently owned JUnit feature examples in teaching order."
                testClassesDirs = demoTests.output.classesDirs
                classpath = demoTests.runtimeClasspath
                useJUnitPlatform { includeTags("presentation") }
                maxParallelForks = 1
                systemProperty("junit.jupiter.execution.parallel.enabled", "false")
                systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$DisplayName")
                outputs.upToDateWhen { false }
                outputs.cacheIf { false }
                jvmArgs("--enable-native-access=ALL-UNNAMED")
                testLogging {
                    events("passed", "failed", "skipped", "standardOut", "standardError")
                    showStandardStreams = true
                }
            }
        }
    }
    if (project.path.startsWith(":demos")) {
        return@subprojects
    }
    // Grouping projects own lifecycle tasks, not fabricated Java/Kotlin source sets.
    // Task collections retain children registered later without eager realization.
    apply(plugin = "base")
    listOf("build", "check").forEach { lifecycle ->
        tasks.named(lifecycle) {
            dependsOn(
                provider {
                    childProjects.values.flatMap { child ->
                        child.tasks.names
                            .filter { it == lifecycle }
                            .map { child.tasks.named(it) }
                    }
                },
            )
        }
    }

    // Core build scripts query Java task providers before sibling evaluation.
    // Preserve that admission for conventional source modules; plugin callbacks
    // also admit source-free Java starters and modules with custom source roots.
    if (layout.projectDirectory
            .dir("src")
            .asFile.isDirectory
    ) {
        apply(plugin = "java")
    }
    plugins.withId("java") {
        apply(plugin = "maven-publish")
        apply(plugin = "jacoco")
        apply(plugin = "com.github.spotbugs")
        if (!path.startsWith(":tools")) {
            plugins.withId("org.jetbrains.kotlin.jvm") {
                apply(plugin = "dev.detekt")
                detekt {
                    baseline = file("$rootDir/config/detekt/${project.name}-baseline.xml")
                    source.setFrom(files("src/main/kotlin"))
                    failOnSeverity = dev.detekt.gradle.extensions.FailOnSeverity.Error
                }
                plugins.withId("dev.detekt") {
                    tasks.named("check") {
                        dependsOn("detektMain")
                    }
                }
            }
        }

        tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
            jdkHome.set(layout.dir(providers.systemProperty("java.home").map(::File)))
        }

        extensions.configure<PublishingExtension> {
            repositories {
                System.getenv("GITHUB_ACTOR")?.let { actor ->
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/Pilleo/mazewall")
                        credentials {
                            username = actor
                            password = System.getenv("GITHUB_TOKEN")
                        }
                    }
                }
            }
        }

        spotbugs {
            ignoreFailures.set(false)
            showStackTraces.set(true)
            showProgress.set(false)
            effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
            reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
            // SpotBugs uses .- for recursive package prefixes; .* stops at one package.
            onlyAnalyze.set(listOf("io.mazewall.-", "demo.vulnapp.-"))
            excludeFilter.set(file("$rootDir/config/spotbugs/exclude.xml"))
        }

        tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
            maxHeapSize.set("1g")
        }

        tasks.matching { it.name.startsWith("spotbugsTest") || it.name.startsWith("spotbugsIntegrationTest") || it.name.startsWith("spotbugsSharedTest") }.configureEach {
            enabled = false
        }
        dependencies {
            "spotbugsPlugins"(
                rootProject.extensions
                    .getByType<VersionCatalogsExtension>()
                    .named("libs")
                    .findLibrary("findsecbugs")
                    .get(),
            )
            "testImplementation"(rootProject.sourceSets["sharedTest"].output)
            "testRuntimeOnly"(rootProject.sourceSets["sharedTest"].output)
        }

        tasks.withType<Test>().configureEach {
            systemProperty("io.mazewall.test", "true")
            if (project.hasProperty("io.mazewall.strictTestTier")) {
                systemProperty("io.mazewall.strictTestTier", project.property("io.mazewall.strictTestTier") as String)
            }
        }

        val jacocoExcludes =
            listOf(
                "**/io/mazewall/RealNative*",
                "**/io/mazewall/RealTransactionManager*",
                "**/io/mazewall/enforcer/JvmFloorWorkload*",
                "**/io/mazewall/enforcer/internal/supervisor/JVMValidationListener*",
                "**/io/mazewall/ffi/networking/SupervisorSeccompNotifInstaller*",
                "**/io/mazewall/enforcer/internal/supervisor/SupervisorSessionHandler*",
                "**/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonEngine*",
                "**/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller*",
                "**/io/mazewall/enforcer/internal/supervisor/SupervisorDaemon*",
                "**/io/mazewall/profiler/internal/engine/ProfilerDaemonEngine*",
                "**/io/mazewall/profiler/internal/engine/RealProfilerTransport*",
                "**/io/mazewall/profiler/internal/ProfilerTraceListener*",
                "**/io/mazewall/profiler/internal/ProfilerDaemonManager*",
                "**/io/mazewall/profiler/triage/DiagnosticTriageRunner*",
                // Privileged standalone acceptance executables are exercised by
                // the Docker kernel gate, not by in-process JaCoCo unit tests.
                "**/io/mazewall/profiler/tierE/stress/*",
                "**/io/mazewall/orchestrator/OrchestratorDaemonKt*",
                "**/io/mazewall/orchestrator/RealGitHubClient*",
                "**/io/mazewall/orchestrator/RealJulesClient*",
                "**/io/mazewall/orchestrator/TelegramBot*",
                "**/io/mazewall/orchestrator/RealOrchestratorEnvironment*",
            )

        val unitTest = tasks.named<Test>("test")
        val childExecutionDirectory = project.layout.buildDirectory.dir("jacoco/unit-children")
        val unitExecutionData =
            files(
                project.layout.buildDirectory.file("jacoco/test.exec"),
                fileTree(childExecutionDirectory) { include("*.exec") },
            )
        if (project.name == "enforcer") {
            val agent = files(configurations.named("jacocoAgent"))
            unitTest.configure {
                inputs.files(agent).withPropertyName("ownedChildJacocoAgent")
                outputs.dir(childExecutionDirectory).withPropertyName("ownedChildExecutionData")
                doFirst {
                    val directory = childExecutionDirectory.get().asFile
                    check(directory.deleteRecursively()) { "Cannot clear owned child coverage directory: $directory" }
                    check(directory.mkdirs()) { "Cannot create fresh owned child coverage directory: $directory" }
                    val agentJar = directory.resolve("jacocoagent.jar")
                    JarFile(agent.singleFile).use { archive ->
                        archive.getInputStream(checkNotNull(archive.getJarEntry("jacocoagent.jar"))).use { input ->
                            agentJar.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    systemProperty("mazewall.test.childCoverage.agent", agentJar.absolutePath)
                    systemProperty("mazewall.test.childCoverage.directory", directory.absolutePath)
                }
            }
        }
        val kernelExecutionData =
            fileTree(project.layout.buildDirectory.dir("jacoco")) {
                include("integrationTest.exec", "integrationTestFreshJvm.exec")
            }
        val combinedExecutionData =
            if (project.name == "platform") {
                files(
                    unitExecutionData,
                    kernelExecutionData,
                    fileTree(rootProject.layout.projectDirectory.dir("enforcer/build/jacoco")).include("*.exec"),
                    fileTree(rootProject.layout.projectDirectory.dir("enforcer/build/jacoco/unit-children")).include("*.exec"),
                )
            } else {
                files(unitExecutionData, kernelExecutionData)
            }

        val mainSourceSet = extensions.getByType<SourceSetContainer>().named("main")
        val coveredClasses =
            mainSourceSet.map { main ->
                main.output.classesDirs.asFileTree
                    .matching { exclude(jacocoExcludes) }
            }

        fun org.gradle.testing.jacoco.tasks.JacocoReport.configureClasses() {
            // Explicit dependencies to satisfy Gradle 9 validation
            // The JacocoReport task uses classDirectories which includes outputs from classes and processResources
            dependsOn(tasks.named("classes"))
            dependsOn(tasks.named("processResources"))
            classDirectories.setFrom(coveredClasses)
            sourceDirectories.setFrom(mainSourceSet.map { it.allSource.srcDirs })
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

        tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
            description = "Generates host-safe unit-test coverage only."
            dependsOn(unitTest)
            mustRunAfter(unitTest)
            executionData.setFrom(unitExecutionData)
            configureClasses()
        }

        tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoKernelTestReport") {
            group = "verification"
            description = "Generates privileged kernel-test coverage only."
            dependsOn(provider { tasks.names.filter { it == "integrationTest" || it == "integrationTestFreshJvm" }.map { tasks.named(it) } })
            executionData.setFrom(kernelExecutionData)
            configureClasses()
        }

        tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoCombinedReport") {
            group = "verification"
            description = "Generates informational combined unit and kernel coverage."
            dependsOn(unitTest)
            dependsOn(provider { tasks.names.filter { it == "integrationTest" || it == "integrationTestFreshJvm" }.map { tasks.named(it) } })
            // Platform's native wrappers are also exercised by enforcer containment
            // tests.  Make those execution-data producers explicit so Gradle does not
            // reject this cross-project report as an implicit dependency.
            if (project.name == "platform") {
                dependsOn(
                    ":enforcer:test",
                    ":enforcer:integrationTest",
                    ":enforcer:integrationTestFreshJvm",
                )
            }
            executionData.setFrom(combinedExecutionData)
            configureClasses()
        }

        tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            description = "Verifies host-safe unit-test coverage only."
            dependsOn(tasks.named("jacocoTestReport"))
            mustRunAfter(tasks.named("jacocoTestReport"))
            dependsOn(tasks.named("classes"))
            dependsOn(tasks.named("processResources"))
            executionData.setFrom(unitExecutionData)
            classDirectories.setFrom(coveredClasses)
            violationRules {
                if (project.name == "enforcer") {
                    rule {
                        element = "BUNDLE"
                        limit {
                            counter = "INSTRUCTION"
                            value = "COVEREDRATIO"
                            minimum = "0.82".toBigDecimal()
                        }
                    }
                } else if (project.name == "profiler") {
                    rule {
                        element = "BUNDLE"
                        limit {
                            counter = "INSTRUCTION"
                            value = "COVEREDRATIO"
                            minimum = "0.70".toBigDecimal()
                        }
                    }
                } else if (project.name == "platform") {
                    rule {
                        element = "BUNDLE"
                        limit {
                            counter = "INSTRUCTION"
                            value = "COVEREDRATIO"
                            // Unit-only baseline measured after removing kernel
                            // execution data; native bridges stay in kernelCheck.
                            minimum = "0.65".toBigDecimal()
                        }
                    }
                } else if (project.name == "orchestrator") {
                    rule {
                        element = "BUNDLE"
                        limit {
                            counter = "INSTRUCTION"
                            value = "COVEREDRATIO"
                            minimum = "0.78".toBigDecimal()
                        }
                    }
                }
            }
        }

        tasks.register<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoKernelCoverageVerification") {
            group = "verification"
            description = "Verifies combined host and privileged-kernel coverage for native contracts."
            dependsOn(tasks.named("jacocoCombinedReport"))
            mustRunAfter(tasks.named("jacocoCombinedReport"))
            dependsOn(tasks.named("classes"))
            executionData.setFrom(combinedExecutionData)
            classDirectories.setFrom(coveredClasses)
            violationRules {
                when (project.name) {
                    "enforcer" ->
                        rule {
                            element = "BUNDLE"
                            limit {
                                counter = "INSTRUCTION"
                                value = "COVEREDRATIO"
                                minimum = "0.82".toBigDecimal()
                            }
                        }
                    "profiler" ->
                        rule {
                            element = "BUNDLE"
                            limit {
                                counter = "INSTRUCTION"
                                value = "COVEREDRATIO"
                                minimum = "0.68".toBigDecimal()
                            }
                        }
                    "platform" -> {
                        rule {
                            element = "CLASS"
                            includes = listOf("io.mazewall.platform.native.LinuxNative")
                            limit {
                                counter = "INSTRUCTION"
                                value = "COVEREDRATIO"
                                minimum = "0.78".toBigDecimal()
                            }
                        }
                        rule {
                            element = "CLASS"
                            includes =
                                listOf(
                                    "io.mazewall.ffi.Layouts",
                                    "io.mazewall.ffi.LayoutValidator",
                                    "io.mazewall.ffi.memory.SegmentPool",
                                    "io.mazewall.ffi.memory.NativeArena",
                                    "io.mazewall.ffi.internal.RealNativeFileSystem",
                                    "io.mazewall.ffi.internal.RealNativeProcess",
                                )
                            limit {
                                counter = "INSTRUCTION"
                                value = "COVEREDRATIO"
                                minimum = "0.70".toBigDecimal()
                            }
                        }
                    }
                }
            }
        }

        plugins.withId("java") {
            tasks.register("unitCheck") {
                group = "verification"
                description = "Runs host-safe unit tests and enforces unit-only coverage."
                dependsOn(unitTest, tasks.named("jacocoTestCoverageVerification"))
            }
            tasks.register("kernelCheck") {
                group = "verification"
                description = "Runs privileged kernel and fresh-JVM integration tests."
                dependsOn(provider { tasks.names.filter { it == "integrationTest" || it == "integrationTestFreshJvm" }.map { tasks.named(it) } })
                dependsOn(tasks.named("jacocoKernelTestReport"))
                dependsOn(tasks.named("jacocoKernelCoverageVerification"))
            }
            tasks.named("check") {
                dependsOn(tasks.withType<Test>())
                dependsOn(tasks.named("jacocoCombinedReport"))
                dependsOn(tasks.named("jacocoTestCoverageVerification"))
                dependsOn(tasks.named("jacocoKernelCoverageVerification"))
            }
        }
    }
}

// The root shared-test source set holds internal native test doubles. Module
// test compilations consume it through a friend path; production compilations
// and published consumers never receive this access.
val sharedTestClasses = layout.buildDirectory.dir("classes/kotlin/sharedTest")
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name.contains("Test", ignoreCase = true)) {
            dependsOn(":compileSharedTestKotlin")
            compilerOptions.freeCompilerArgs.addAll(
                sharedTestClasses.map { listOf("-Xfriend-paths=${it.asFile.absolutePath}") },
            )
        }
    }
}

// Dependency collections can realize unrelated tasks; resolve only selected registered names.
tasks.register("unitCheck") {
    group = "verification"
    description = "Runs host-safe tests, coverage, lint, and static analysis for participating sources."
    dependsOn(
        provider {
            tasks.names
                .filter {
                    it in setOf("test", "ktlintCheck", "detektMain", "spotbugsMain", "detektWarningRatchet", "checkGradleLazyResolution", "checkCiCachePolicy", "validateBacklogIndex")
                }.map { tasks.named(it) }
        },
    )
    dependsOn(
        provider {
            subprojects.filterNot { it.path.startsWith(":demos") }.flatMap { child ->
                child.tasks.names
                    .filter { it in setOf("unitCheck", "ktlintCheck", "detektMain", "spotbugsMain") }
                    .map { child.tasks.named(it) }
            }
        },
    )
}

tasks.register("kernelCheck") {
    group = "verification"
    description = "Runs every module's privileged kernel-quality gate."
    dependsOn(
        provider {
            subprojects.filterNot { it.path.startsWith(":demos") }.flatMap { child ->
                child.tasks.names
                    .filter { it == "kernelCheck" }
                    .map { child.tasks.named(it) }
            }
        },
    )
}

// BEGIN explicit build-logic gate
val buildLogicCheck =
    tasks.register<GradleBuild>("buildLogicCheck") {
        group = "verification"
        description = "Runs the complete build-logic verification suite."
        setDir(layout.projectDirectory.dir("buildSrc").asFile)
        setBuildName("build-logic-verification")
        setTasks(listOf("verificationActionsTest"))
        startParameter.maxWorkerCount = 1
        startParameter.isParallelProjectExecutionEnabled = false
    }
tasks.named("check") { dependsOn(buildLogicCheck) }
// Ordering does not select the gate on help, compilation or assemble paths.
allprojects {
    tasks.configureEach {
        if (path != ":buildLogicCheck") {
            mustRunAfter(":buildLogicCheck")
        }
    }
}
// END explicit build-logic gate

tasks.register("mergeCheck") {
    group = "verification"
    description = "Runs the complete host and privileged-kernel merge gate."
    dependsOn(tasks.named("check"), tasks.named("unitCheck"), tasks.named("kernelCheck"), "verifyGradleReproducibility")
}

tasks.register<VerifyDependencyState>("verifyGradleReproducibility") {
    group = "verification"
    description = "Verifies that dependency locks and checksum metadata are checked in."
    verificationMetadata.set(layout.projectDirectory.file("gradle/verification-metadata.xml"))
    val expectedLockfiles =
        listOf(
            "gradle.lockfile",
            "settings-gradle.lockfile",
            "buildSrc/gradle.lockfile",
            "platform/gradle.lockfile",
            "enforcer/gradle.lockfile",
            "profiler/gradle.lockfile",
            "portals/runtime/gradle.lockfile",
            "portals/codegen/gradle.lockfile",
            "portals/worker/gradle.lockfile",
            "demos/cli-demo/gradle.lockfile",
            "demos/agent-sandbox-demo/gradle.lockfile",
            "demos/vulnerable-web-app/gradle.lockfile",
        ).map(layout.projectDirectory::file)
    lockfiles.from(expectedLockfiles)
}

tasks.named("build") {
    dependsOn(tasks.named("mergeCheck"))
}

val triageRuntimeClasspath =
    configurations.create("triageRuntimeClasspath") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    add(triageRuntimeClasspath.name, project(":profiler"))
}

tasks.register<JavaExec>("runTriage") {
    group = "verification"
    description = "Gathers telemetry for an explicitly named failed Gradle task."
    classpath = triageRuntimeClasspath
    mainClass.set("io.mazewall.profiler.triage.DiagnosticTriageRunner")
    val failedTask = providers.gradleProperty("triage.failedTask").orElse("unknown")
    val reportFile =
        layout.buildDirectory.file(
            failedTask.map { taskPath ->
                val safePath = taskPath.trim(':').replace(Regex("[^A-Za-z0-9._-]+"), "-").ifEmpty { "unknown" }
                if (taskPath == "unknown") "triage_report.json" else "reports/triage/$safePath/report.json"
            },
        )
    val triageArguments = objects.newInstance<TriageArguments>()
    triageArguments.failedTaskPath.set(failedTask)
    triageArguments.reportFile.set(reportFile)
    argumentProviders.add(triageArguments)
}

objects.newInstance<io.mazewall.build.TestFailureTriage>().wire(project, tasks.named("runTriage"))

// Resolves the hooks directory for both normal checkouts (.git is a directory) and
// linked git worktrees (.git is a "gitdir:" pointer file whose target stores the common
// directory in its `commondir` file). Hooks belong to the common dir in both cases.
val gitHooksDir: File by lazy {
    val dotGit = rootDir.resolve(".git")
    if (dotGit.isDirectory) {
        dotGit.resolve("hooks")
    } else {
        val worktreeGitDir =
            File(
                dotGit
                    .readText()
                    .trim()
                    .removePrefix("gitdir:")
                    .trim(),
            )
        val commonDirRef = worktreeGitDir.resolve("commondir").readText().trim()
        val commonDir =
            if (File(commonDirRef).isAbsolute) {
                File(commonDirRef)
            } else {
                worktreeGitDir.resolve(commonDirRef).canonicalFile
            }
        commonDir.resolve("hooks")
    }
}

tasks.register<Copy>("installGitHooks") {
    group = "git"
    description = "Installs the pre-commit audit and verification hook"
    from("$rootDir/scripts/hooks/pre-commit") {
        rename { "pre-commit" }
    }
    into(gitHooksDir)
    val hookFile = gitHooksDir.resolve("pre-commit")
    doLast {
        hookFile.setExecutable(true)
    }
}

tasks.named("check") {
    findProject(":tools:orchestrator")?.let { orchestrator ->
        dependsOn(orchestrator.tasks.named("checkBacklog"))
    }
}

tasks.register("updateDiagrams") {
    group = "documentation"
    description = "Regenerates the checked-in class diagrams."
    dependsOn(":enforcer:generateClassDiagrams", ":profiler:generateClassDiagrams")
}

/**
 * Checked-in PlantUML/SVG files are documentation artifacts, not build outputs. Keeping
 * regeneration opt-in means that a normal verification run cannot silently dirty the tree.
 */
tasks.register("updateClassDiagrams") {
    group = "documentation"
    description = "Regenerates the checked-in enforcer, profiler, and platform class diagrams"
    dependsOn(":enforcer:generateClassDiagrams", ":profiler:generateClassDiagrams", ":platform:generateClassDiagrams")
}

/**
 * The reviewed warning inventory is a ceiling, rather than permission for new warning-level
 * debt. Adjusting a budget is an intentional, reviewable quality decision.
 */
val detektWarningModules =
    mapOf(
        "enforcer" to ":enforcer",
        "platform" to ":platform",
        "portals-codegen" to ":portals:codegen",
        "portals-worker" to ":portals:worker",
        "portals-runtime" to ":portals:runtime",
        "profiler" to ":profiler",
    )

tasks.register("detektWarningRatchet") {
    group = "verification"
    description = "Fails when a production module adds Detekt warning-level debt"
    dependsOn(detektWarningModules.values.map { path -> "$path:detektMain" })
    val reports =
        detektWarningModules.mapValues { (_, path) ->
            project(path)
                .layout.buildDirectory
                .file("reports/detekt/main.xml")
                .get()
                .asFile
        }
    val inventories = detektWarningModules.mapValues { (inventory, _) -> file("config/detekt/warnings/$inventory.txt") }
    inputs.files(reports.values, inventories.values)
    doLast(io.mazewall.build.DetektWarningRatchetAction(reports, inventories))
}

tasks.named("check") {
    dependsOn("detektWarningRatchet", "validateBacklogIndex")
}

/**
 * The checked-in Kotlin ABI dumps are the release contract for the supported
 * libraries. Keep implementation packages out of that contract even when a
 * declaration is accidentally made public during a future refactor.
 */
val verifySupportedApiSurface =
    tasks.register("verifySupportedApiSurface") {
        group = "verification"
        description = "Rejects implementation packages and types from published Kotlin ABI snapshots"
        dependsOn(":platform:checkKotlinAbi", ":enforcer:checkKotlinAbi", ":profiler:checkKotlinAbi")
        val snapshots =
            mapOf(
                file("enforcer/api/enforcer.api") to
                    listOf(
                        "io/mazewall/enforcer/supervisor/",
                        "io/mazewall/ffi/",
                        "io/mazewall/landlock/",
                        "io/mazewall/seccomp/",
                        "io/mazewall/enforcer/engine/",
                    ),
                file("profiler/api/profiler.api") to
                    listOf(
                        "io/mazewall/profiler/engine/",
                        "io/mazewall/profiler/collector/",
                        "io/mazewall/profiler/compiler/",
                        "io/mazewall/profiler/iterative/",
                        "io/mazewall/profiler/strace/",
                        "io/mazewall/profiler/triage/",
                        "io/mazewall/profiler/tierE/",
                        "io/mazewall/profiler/ffi/",
                        "io/mazewall/ffi/",
                        "io/mazewall/seccomp/",
                    ),
            )
        // Kotlin must emit a public JVM class for the internal Tier-E daemon main
        // method. It is an executable entry point, not a callable Kotlin API.
        val internalLauncher = "io/mazewall/profiler/tierE/daemon/TierEKotlinDaemonMain"
        inputs.files(snapshots.keys)
        doLast {
            val violations =
                snapshots.flatMap { (snapshot, forbiddenSymbols) ->
                    val content = snapshot.readText().replace(internalLauncher, "")
                    forbiddenSymbols.filter(content::contains).map { "$snapshot exposes $it" }
                }
            check(violations.isEmpty()) {
                "Published API contains implementation declarations:\n${violations.joinToString("\n")}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifySupportedApiSurface)
    dependsOn(":platform:checkKotlinAbi", ":enforcer:checkKotlinAbi", ":profiler:checkKotlinAbi")
}

/**
 * Structural release gate for the 0.1.0 domain-first package cutover.  Kotlin
 * does not require source directories to mirror packages, so check the parsed
 * package declaration rather than relying on a directory layout convention.
 */
val verifyPackageTopology =
    tasks.register("verifyPackageTopology") {
        group = "verification"
        description = "Rejects retired flat production package namespaces"
        val sourceRoots =
            listOf(
                "platform/src/main/kotlin",
                "enforcer/src/main/kotlin",
                "profiler/src/main/kotlin",
                "portals/runtime/src/main/kotlin",
                "portals/codegen/src/main/kotlin",
                "portals/worker/src/main/kotlin",
            ).map(::file)
        val repositoryRoot = layout.projectDirectory.asFile
        val retiredPackages =
            setOf(
                "io.mazewall",
                "io.mazewall.core",
                "io.mazewall.enforcer.supervisor",
                "io.mazewall.profiler",
                "io.mazewall.profiler.engine",
                "io.mazewall.profiler.attribution",
            )
        inputs.files(sourceRoots)
        doLast {
            val packagePattern = Regex("(?m)^package\\s+([A-Za-z0-9_.]+)\\s*$")
            val violations =
                sourceRoots.flatMap { root ->
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .mapNotNull { source ->
                            val packageName = packagePattern.find(source.readText())?.groupValues?.get(1)
                            packageName?.takeIf(retiredPackages::contains)?.let { "${source.relativeTo(repositoryRoot)} declares retired package $it" }
                        }.toList()
                }
            check(violations.isEmpty()) {
                "Domain-first package topology violations:\n${violations.joinToString("\n")}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyPackageTopology)
}

tasks.register("validateBacklogIndex") {
    group = "verification"
    description = "Ensures the backlog index has one authoritative summary line"
    val index = layout.projectDirectory.file("docs/internals/backlog/README.md").asFile
    val issues = fileTree(layout.projectDirectory.dir("docs/internals/backlog")) { include("**/issue-*.md") }
    inputs.file(index)
    inputs.files(issues)
    doLast(io.mazewall.build.ValidateBacklogIndexAction(index))
}

val refactorFirstVersion = "0.10.0"
val refactorFirstSourceRoots =
    listOf(
        "platform/src/main",
        "enforcer/src/main",
        "profiler/src/main",
        "portals/runtime/src/main",
        "portals/codegen/src/main",
        "portals/worker/src/main",
    )
val refactorFirstRunner = layout.projectDirectory.file("scripts/refactor_first.sh")
val refactorFirstOutput = layout.buildDirectory.file("reports/refactorFirst/refactor-first-report.html")
val refactorFirstCommit =
    providers
        .exec {
            workingDir(rootDir)
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText
        .map(String::trim)

val refactorFirstPreflight =
    tasks.register<Exec>("refactorFirstPreflight") {
        group = "verification"
        description = "Checks prerequisites and requires committed production sources for RefactorFirst"
        outputs.upToDateWhen { false }
        commandLine(refactorFirstRunner.asFile.absolutePath, "--check")
    }

tasks.register<Exec>("refactorFirstReport") {
    group = "verification"
    description = "Generates an isolated RefactorFirst HTML report for committed production sources"
    dependsOn(refactorFirstPreflight)
    inputs.file(refactorFirstRunner)
    inputs.property("refactorFirstVersion", refactorFirstVersion)
    inputs.property("sourceRoots", refactorFirstSourceRoots)
    inputs.property("commit", refactorFirstCommit)
    outputs.file(refactorFirstOutput)
    commandLine(
        refactorFirstRunner.asFile.absolutePath,
        "--output",
        refactorFirstOutput.get().asFile.absolutePath,
    )
}

val presentationStages =
    listOf(":demos:cli-demo", ":demos:agent-sandbox-demo") +
        listOfNotNull(findProject(":demos:koog-contained-tool")?.path)

tasks.register("presentationTest") {
    group = "verification"
    description = "Teach contracts, owned execution, supervised tools, then optional process Portals."
    dependsOn(presentationStages.map { "$it:presentationTest" })
    doFirst {
        logger.lifecycle("Presentation stages: {}. Finished Boot/Security and web examples have separate owning tasks.", presentationStages)
    }
}
gradle.projectsEvaluated {
    presentationStages.zipWithNext().forEach { (before, after) ->
        project(after).tasks.named("presentationTest") { mustRunAfter("$before:presentationTest") }
    }
}

// Wipes every module's JUnit XML/binary result directories (all test variants).
// Use before bisecting or comparing runs: stale result XMLs from a previous source
// revision otherwise produce phantom failures when reading reports
// (issue-20260823-172001).
tasks.register("cleanAllTestResults") {
    group = "verification"
    description = "Deletes all modules' build/test-results directories (test, integrationTest, integrationTestFreshJvm)."
    doLast {
        subprojects { project.delete(layout.buildDirectory.dir("test-results")) }
        delete(layout.buildDirectory.dir("test-results"))
    }
}
