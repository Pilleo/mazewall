import org.gradle.api.publish.PublishingExtension
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    // Disable detekt globally
    tasks.configureEach {
        if (name.contains("detekt", ignoreCase = true)) {
            enabled = false
        }
    }

    if (!project.path.startsWith(":demos")) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.3.1")
            verbose.set(true)
            outputToConsole.set(true)
            coloredOutput.set(true)
            reporters {
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
            }
        }
        // Disable ktlint formatting/checking tasks because the ktlint engine (even 1.3.1+)
        // fails to parse Kotlin 2.x context parameters syntax ("context(arena: Arena)").
        // TODO: Re-enable these tasks once KtLint officially supports named context parameters syntax in Kotlin 2.4+.
        tasks.configureEach {
            if (name.contains("ktlint", ignoreCase = true)) {
                enabled = false
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
            // Only log failed tests to keep console clean
            testLogging {
                events = setOf(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
                showStandardStreams = false
            }

            addTestOutputListener { descriptor, event ->
                val testId = "${descriptor.className ?: "UnknownClass"}.${descriptor.name}"
                failedTestsOutputs.getOrPut(testId) { StringBuilder() }.append(event.message)
            }

            addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) {}
                override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
                override fun beforeTest(testDescriptor: TestDescriptor) {}
                override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                    val testId = "${testDescriptor.className ?: "UnknownClass"}.${testDescriptor.name}"
                    if (result.resultType == TestResult.ResultType.FAILURE) {
                        val output = failedTestsOutputs[testId]?.toString()
                        if (!output.isNullOrBlank()) {
                            // Using println(Any?) which maps to lifecycle
                            println("\n=== Captured stdout/stderr for $testId ===")
                            println(output)
                            println("===========================================\n")
                        }
                    }
                    failedTestsOutputs.remove(testId)
                }
            })
        }
    }

    // Ensure code is formatted before compilation or check to prevent build failures
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (!project.path.startsWith(":demos")) {
            dependsOn("ktlintFormat")
        }
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
            freeCompilerArgs.add("-Xcontext-parameters")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(22)
    }

    tasks.matching { it.name == "ktlintCheck" || it.name == "ktlintTestSourceSetCheck" || it.name == "ktlintMainSourceSetCheck" }.configureEach {
        if (!project.path.startsWith(":demos")) {
            dependsOn("ktlintFormat")
        }
    }

    // Also format Kotlin scripts (like build.gradle.kts)
    tasks.matching { it.name == "kotlinSourcesJar" }.configureEach {
        if (!project.path.startsWith(":demos")) {
            dependsOn("ktlintFormat")
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Configure global timeout to prevent infinite test hangs (generous for testcontainers pulls)
    systemProperty("junit.jupiter.execution.timeout.default", "2 m")
    // Run timed tests on a separate thread so JUnit can interrupt hangs and report their stack trace.
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    
    testLogging {
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
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
    failOnSeverity = dev.detekt.gradle.extensions.FailOnSeverity.Never
}

subprojects {
    if (project.path.startsWith(":demos")) {
        return@subprojects
    }
    apply(plugin = "java")
    apply(plugin = "maven-publish")
    apply(plugin = "base")
    apply(plugin = "jacoco")
    apply(plugin = "dev.detekt")
    apply(plugin = "com.github.spotbugs")

    detekt {
        baseline = file("$rootDir/config/detekt/${project.name}-baseline.xml")
        source.setFrom(files("src/main/kotlin"))
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

    tasks.matching { it.name.startsWith("spotbugsTest") || it.name.startsWith("spotbugsIntegrationTest") }.configureEach {
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
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion =
            rootProject.extensions
                .getByType<VersionCatalogsExtension>()
                .named("libs")
                .findVersion("jacoco")
                .get()
                .requiredVersion
    }

    tasks.withType<Test>().configureEach {
        systemProperty("io.mazewall.test", "true")
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
            "**/io/mazewall/orchestrator/OrchestratorDaemonKt*",
            "**/io/mazewall/orchestrator/RealGitHubClient*",
            "**/io/mazewall/orchestrator/RealJulesClient*",
            "**/io/mazewall/orchestrator/TelegramBot*",
            "**/io/mazewall/orchestrator/RealOrchestratorEnvironment*",
        )

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
        // Enforce ordering so we aggregate execution data from both host unit tests and container integration tests
        mustRunAfter(rootProject.tasks.named("test"))
        dependsOn(tasks.withType<Test>())
        mustRunAfter(tasks.withType<Test>())
        executionData.setFrom(fileTree(project.layout.buildDirectory.dir("jacoco")).include("*.exec"))
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

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
        // Enforce ordering so we aggregate execution data from both host unit tests and container integration tests
        mustRunAfter(rootProject.tasks.named("test"))
        dependsOn(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>())
        mustRunAfter(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>())
        executionData.setFrom(fileTree(project.layout.buildDirectory.dir("jacoco")).include("*.exec"))
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
                rule {
                    element = "CLASS"
                    includes = listOf("io.mazewall.landlock.Landlock*")
                    limit {
                        counter = "INSTRUCTION"
                        value = "COVEREDRATIO"
                        minimum = "0.0".toBigDecimal()
                    }
                }
            } else if (project.name == "profiler") {
                rule {
                    element = "BUNDLE"
                    limit {
                        counter = "INSTRUCTION"
                        value = "COVEREDRATIO"
                        minimum = "0.84".toBigDecimal()
                    }
                }
            } else if (project.name == "platform") {
                // Keep coverage from the extracted native/FFM and shared seccomp code gated.
                // This is the platform module's current unit-test baseline, rounded down so
                // small compiler instrumentation changes do not make the threshold flaky.
                rule {
                    element = "BUNDLE"
                    limit {
                        counter = "INSTRUCTION"
                        value = "COVEREDRATIO"
                        minimum = "0.30".toBigDecimal()
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

    plugins.withId("java") {
        tasks.named("check") {
            dependsOn(tasks.withType<Test>())
            dependsOn(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>())
            dependsOn(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>())
        }
    }
}

evaluationDependsOn(":profiler")

tasks.register<JavaExec>("runTriage") {
    group = "verification"
    description = "Gathers system telemetry and diagnostics on failure."
    classpath = files(":profiler:classes", ":profiler:runtimeClasspath")
    mainClass.set("io.mazewall.profiler.triage.DiagnosticTriageRunner")

    val testFailures = objects.listProperty<Boolean>().apply {
        set(provider {
            subprojects.flatMap { it.tasks.withType<Test>() }.map { it.state.failure != null }
        })
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

val installGitHooks by tasks.registering(Copy::class) {
    group = "git"
    description = "Installs the pre-commit audit and verification hook"
    from("$rootDir/scripts/git-audit-hook.sh") {
        rename { "pre-commit" }
    }
    into(file("$rootDir/.git/hooks"))
    val targetFile = file("$rootDir/.git/hooks/pre-commit")
    doLast {
        targetFile.setExecutable(true)
    }
}

tasks.named("check") {
    dependsOn(installGitHooks)
    dependsOn(":tools:orchestrator:checkBacklog")
    dependsOn(tasks.named("refactorFirstReport"))
}





tasks.register<Exec>("refactorFirstReport") {
    group = "verification"
    description = "Generates a RefactorFirst HTML report using Maven"
    commandLine("mvn", "org.hjug.refactorfirst.plugin:refactor-first-maven-plugin:0.9.0:htmlReport")
}
