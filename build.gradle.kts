import org.gradle.api.publish.PublishingExtension
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Demos and :tools are operator/control-plane code; ktlint and detekt stay off them. */
fun Project.skipsKotlinStyleTools(): Boolean = path.startsWith(":demos") || path.startsWith(":tools")

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.dependencyCheck)
    alias(libs.plugins.pitest) apply false
    id("jacoco")
    id("base")
}

allprojects {
    group = "io.mazewall"
    version = "0.0.1-prealpha-SNAPSHOT"

    repositories {
        mavenCentral()
    }

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
        // Apply hang-prevention timeouts to every project's Test tasks, not just :test.
        systemProperty("junit.jupiter.execution.timeout.default", "2 m")
        systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
        if (project.path in setOf(":enforcer", ":platform", ":profiler")) {
            useJUnitPlatform()
            jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "-Xmx256m",
                "-Xms128m",
                "-Dfile.encoding=UTF-8",
                "-Dsun.jnu.encoding=UTF-8",
            )
            systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
        }

        val failedTestsOutputs = ConcurrentHashMap<String, StringBuilder>()

        if (isVerbose) {
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
                failedTestsOutputs.getOrPut(testId) { StringBuilder() }.append(event.message)
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

    // Ensure code is formatted before compilation or check to prevent build failures
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (!skipsKotlinStyleTools()) {
            dependsOn("ktlintFormat")
        }
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
            freeCompilerArgs.add("-Xcontext-parameters")
            freeCompilerArgs.add("-opt-in=io.mazewall.MazewallInternal")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(22)
    }

    tasks.matching { it.name == "ktlintCheck" || it.name == "ktlintTestSourceSetCheck" || it.name == "ktlintMainSourceSetCheck" }.configureEach {
        if (!skipsKotlinStyleTools()) {
            dependsOn("ktlintFormat")
        }
    }

    // Also format Kotlin scripts (like build.gradle.kts)
    tasks.matching { it.name == "kotlinSourcesJar" }.configureEach {
        if (!skipsKotlinStyleTools()) {
            dependsOn("ktlintFormat")
        }
    }
}

tasks.matching { it.name.startsWith("spotbugsTest") || it.name.startsWith("spotbugsIntegrationTest") || it.name.startsWith("spotbugsSharedTest") }.configureEach {
    enabled = false
}

val checkGradleLazyResolution by tasks.registering(Exec::class) {
    group = "verification"
    description = "Reject eager Gradle configuration resolution in Kotlin build scripts"
    commandLine("bash", "$rootDir/scripts/check_gradle_lazy_resolution.sh")
}

tasks.named("check") {
    dependsOn(checkGradleLazyResolution)
}
dependencies {
    testImplementation(kotlin("test"))
}

sourceSets {
    val sharedTest by creating {
        kotlin.srcDir("src/sharedTest/kotlin")
        resources.srcDir("src/sharedTest/resources")
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
    "sharedTestImplementation"(libs.junit.jupiter.api)
    "sharedTestImplementation"("org.junit.platform:junit-platform-launcher:1.10.2")
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
        ossIndexEnabled = false
    }
    // Skip checking demo projects since they are deliberately vulnerable
    skipProjects = listOf(":demos:cli-demo", ":demos:vulnerable-web-app")
    // Only scan production compile and runtime configurations to avoid scanning build tooling like detekt and ktlint
    scanConfigurations = listOf("compileClasspath", "runtimeClasspath")
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
    if (project.path.startsWith(":demos")) {
        return@subprojects
    }
    apply(plugin = "java")
    apply(plugin = "maven-publish")
    apply(plugin = "base")
    apply(plugin = "jacoco")
    apply(plugin = "com.github.spotbugs")
    if (!path.startsWith(":tools")) {
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

    tasks.configureEach {
        if (name.startsWith("detekt")) {
            try {
                val method = this.javaClass.getMethod("getJdkHome")
                val property = method.invoke(this) as org.gradle.api.file.DirectoryProperty
                property.set(layout.projectDirectory.dir(providers.systemProperty("java.home")))
            } catch (_: NoSuchMethodException) {
                // Ignore tasks that do not have getJdkHome
            }
        }
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
        onlyAnalyze.set(listOf("io.mazewall.*", "demo.vulnapp.*"))
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
            "**/io/mazewall/enforcer/supervisor/JVMValidationListener*",
            "**/io/mazewall/ffi/networking/SupervisorSeccompNotifInstaller*",
            "**/io/mazewall/enforcer/supervisor/SupervisorSessionHandler*",
            "**/io/mazewall/enforcer/supervisor/SupervisorDaemonEngine*",
            "**/io/mazewall/enforcer/supervisor/SupervisorInstaller*",
            "**/io/mazewall/enforcer/supervisor/SupervisorDaemon*",
            "**/io/mazewall/profiler/engine/ProfilerDaemonEngine*",
            "**/io/mazewall/profiler/engine/RealProfilerTransport*",
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
    val unitExecutionData = project.layout.buildDirectory.file("jacoco/test.exec")
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
            )
        } else {
            files(unitExecutionData, kernelExecutionData)
        }

    fun org.gradle.testing.jacoco.tasks.JacocoReport.configureClasses() {
        // Explicit dependencies to satisfy Gradle 9 validation
        // The JacocoReport task uses classDirectories which includes outputs from classes and processResources
        dependsOn(tasks.named("classes"))
        tasks.findByName("processResources")?.let { processResources ->
            dependsOn(processResources)
        }
        classDirectories.setFrom(
            files(
                classDirectories.files.map {
                    fileTree(it) {
                        exclude(jacocoExcludes)
                    }
                },
            ),
        )
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
        dependsOn(tasks.matching { it.name == "integrationTest" || it.name == "integrationTestFreshJvm" })
        executionData.setFrom(kernelExecutionData)
        configureClasses()
    }

    tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoCombinedReport") {
        group = "verification"
        description = "Generates informational combined unit and kernel coverage."
        dependsOn(unitTest)
        dependsOn(tasks.matching { it.name == "integrationTest" || it.name == "integrationTestFreshJvm" })
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
        tasks.findByName("processResources")?.let { processResources -> dependsOn(processResources) }
        executionData.setFrom(unitExecutionData)
        classDirectories.setFrom(
            files(
                classDirectories.files.map {
                    fileTree(it) {
                        exclude(jacocoExcludes)
                    }
                },
            ),
        )
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
        classDirectories.setFrom(
            files(
                classDirectories.files.map {
                    fileTree(it) { exclude(jacocoExcludes) }
                },
            ),
        )
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
                        includes = listOf("io.mazewall.LinuxNative")
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
            dependsOn(tasks.matching { it.name == "integrationTest" || it.name == "integrationTestFreshJvm" })
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

tasks.register("unitCheck") {
    group = "verification"
    description = "Runs every module's host-safe unit-quality gate."
    dependsOn(subprojects.filterNot { it.path.startsWith(":demos") }.map { "${it.path}:unitCheck" })
}

tasks.register("kernelCheck") {
    group = "verification"
    description = "Runs every module's privileged kernel-quality gate."
    dependsOn(subprojects.filterNot { it.path.startsWith(":demos") }.map { "${it.path}:kernelCheck" })
}

evaluationDependsOn(":profiler")

val triageRuntimeClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(triageRuntimeClasspath.name, project(":profiler"))
}

tasks.register<JavaExec>("runTriage") {
    group = "verification"
    description = "Gathers system telemetry and diagnostics on failure."
    classpath = triageRuntimeClasspath
    mainClass.set("io.mazewall.profiler.triage.DiagnosticTriageRunner")

    val testFailures =
        objects.listProperty<Boolean>().apply {
            set(
                provider {
                    subprojects.flatMap { it.tasks.withType<Test>() }.map { it.state.failure != null }
                },
            )
        }

    // Only run this diagnostic triage task if the test execution actually failed.
    onlyIf {
        testFailures.get().any { it }
    }
}

// Wire the triage runner to finalize test execution across all subprojects
subprojects {
    tasks.withType<Test>().configureEach {
        finalizedBy(rootProject.tasks.named("runTriage"))
    }
}

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

val installGitHooks by tasks.registering(Copy::class) {
    group = "git"
    description = "Installs the pre-commit audit and verification hook"
    from("$rootDir/scripts/git-audit-hook.sh") {
        rename { "pre-commit" }
    }
    into(gitHooksDir)
    val hookFile = gitHooksDir.resolve("pre-commit")
    doLast {
        hookFile.setExecutable(true)
    }
}

tasks.named("check") {
    dependsOn(installGitHooks)
    findProject(":tools:orchestrator")?.let { orchestrator ->
        dependsOn(orchestrator.tasks.named("checkBacklog"))
    }
    dependsOn(tasks.named("refactorFirstReport"))
}

// RefactorFirst's underlying maven/jgit tooling cannot resolve linked git worktrees (.git
// file), so the informational report is skipped there; normal checkouts are unaffected.
// The worktree branch is decided at configuration time (plain file inspection); the 'mvn'
// availability probe stays inside the execution-time onlyIf because Gradle forbids spawning
// external processes during configuration.
val refactorFirstPlainCheckout = rootDir.resolve(".git").isDirectory

tasks.register<Exec>("refactorFirstReport") {
    group = "verification"
    description = "Generates a RefactorFirst HTML report using Maven"
    if (!refactorFirstPlainCheckout) {
        description = "$description (skipped in linked git worktrees: unsupported by jgit)"
        onlyIf { false }
        commandLine("refactor-first-skipped")
    } else {
        onlyIf {
            runCatching {
                ProcessBuilder("mvn", "-v").redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
            }.getOrDefault(false)
        }
        commandLine("mvn", "org.hjug.refactorfirst.plugin:refactor-first-maven-plugin:0.9.0:htmlReport")
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
