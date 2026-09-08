import io.mazewall.build.TriageArguments
import io.mazewall.build.VerifyDependencyState
import java.io.File
import java.util.concurrent.ConcurrentHashMap

plugins {
    id("mazewall.quality-conventions")
    id("mazewall.publishing-conventions")
    alias(libs.plugins.dependencyCheck)
    alias(libs.plugins.pitest) apply false
    id("base")
}

allprojects {
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
}

val checkGradleLazyResolution =
    tasks.register<Exec>("checkGradleLazyResolution") {
        group = "verification"
        description = "Reject eager Gradle configuration resolution in Kotlin build scripts"
        commandLine("bash", "$rootDir/scripts/check_gradle_lazy_resolution.sh")
    }

tasks.named("check") {
    dependsOn(checkGradleLazyResolution)
}

tasks.register("format") {
    group = "formatting"
    description = "Formats Kotlin sources in projects that opt into KtLint."
    dependsOn("ktlintFormat")
    dependsOn(
        listOf(":platform", ":enforcer", ":profiler", ":portal", ":portal-codegen", ":portal-worker")
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
    "sharedTestImplementation"(libs.junit.platform.launcher)
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
    plugins.withId("mazewall.quality-conventions") {
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
        spotbugs {
            onlyAnalyze.set(listOf("io.mazewall.*", "demo.vulnapp.*"))
            excludeFilter.set(file("$rootDir/config/spotbugs/exclude.xml"))
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
        val mainOutput = sourceSets.named("main").map { it.output }
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
            classDirectories.setFrom(
                mainOutput.map { output ->
                    output.classesDirs.asFileTree.matching {
                        exclude(jacocoExcludes)
                    }
                },
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
            executionData.setFrom(unitExecutionData)
            classDirectories.setFrom(
                mainOutput.map { output ->
                    output.classesDirs.asFileTree.matching {
                        exclude(jacocoExcludes)
                    }
                },
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
                mainOutput.map { output ->
                    output.classesDirs.asFileTree.matching {
                        exclude(jacocoExcludes)
                    }
                },
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
            "portal/gradle.lockfile",
            "portal-codegen/gradle.lockfile",
            "portal-worker/gradle.lockfile",
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

    val failedTask = providers.gradleProperty("triage.failedTask")
    val reportFile =
        layout.buildDirectory.file(
            failedTask.map { taskPath ->
                val safePath = taskPath.trim(':').replace(Regex("[^A-Za-z0-9._-]+"), "-").ifEmpty { "unknown" }
                "reports/triage/$safePath/report.json"
            },
        )
    val triageArguments = objects.newInstance<TriageArguments>()
    triageArguments.failedTaskPath.set(failedTask)
    triageArguments.reportFile.set(reportFile)
    argumentProviders.add(triageArguments)
    onlyIf("a failed task was supplied") { failedTask.isPresent }
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

tasks.register<Copy>("installGitHooks") {
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
    findProject(":tools:orchestrator")?.let { orchestrator ->
        dependsOn(orchestrator.tasks.named("checkBacklog"))
    }
}

tasks.register("updateDiagrams") {
    group = "documentation"
    description = "Regenerates the checked-in class diagrams."
    dependsOn(":enforcer:generateClassDiagrams", ":profiler:generateClassDiagrams")
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
