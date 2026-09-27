import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.math.BigDecimal
import javax.inject.Inject

abstract class GenerateInvocationStateBtf
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        @get:InputFile abstract val source: RegularFileProperty

        @get:OutputFile abstract val objectFile: RegularFileProperty

        @get:OutputFile abstract val resource: RegularFileProperty

        @TaskAction
        fun generate() {
            objectFile
                .get()
                .asFile.parentFile
                .mkdirs()
            resource
                .get()
                .asFile.parentFile
                .mkdirs()
            execOperations.exec {
                commandLine("clang", "-target", "bpf", "-g", "-O2", "-c", source.get().asFile.absolutePath, "-o", objectFile.get().asFile.absolutePath)
            }
            execOperations.exec {
                commandLine("llvm-objcopy", "--dump-section", ".BTF=${resource.get().asFile.absolutePath}", objectFile.get().asFile.absolutePath)
            }
        }
    }

plugins {
    kotlin("jvm")
    application
    id("info.solidsoft.pitest")
    alias(libs.plugins.plantuml)
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    jvmToolchain(25)
    explicitApi()
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}

application {
    mainClass.set("io.mazewall.profiler.tierE.daemon.TierEKotlinDaemonMain")
    applicationName = "tier-e-daemon"
    applicationDefaultJvmArgs =
        listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Dfile.encoding=UTF-8",
            "-Dsun.jnu.encoding=UTF-8",
        )
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/resources"))
    }
    test {
        java.srcDir(rootProject.file("src/sharedTest/kotlin"))
        kotlin.exclude("io/mazewall/testing/containers/ContainerEndpointDiscovery.kt")
    }
    create("integrationTest") {
        java.srcDir(rootProject.file("src/sharedTest/kotlin"))
        kotlin.exclude("io/mazewall/testing/containers/ContainerEndpointDiscovery.kt")
        compileClasspath += main.get().output + test.get().output
        runtimeClasspath += main.get().output + test.get().output
    }
}

// Developer-only verification utilities are not part of the published profiler
// jar. Ordinary Kotlin unit tests can exercise them without Docker/Podman.
val toolingSourceSet =
    sourceSets.create("tooling") {
        kotlin.srcDir(rootProject.file("src/sharedTest/kotlin/io/mazewall/testing/containers"))
        kotlin.exclude("EnabledIfRootfulContainerAvailable.kt")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
sourceSets.test {
    compileClasspath += toolingSourceSet.output
    runtimeClasspath += toolingSourceSet.output
}
sourceSets.named("integrationTest") {
    compileClasspath += toolingSourceSet.output
    runtimeClasspath += toolingSourceSet.output
}
configurations.named("toolingImplementation") {
    extendsFrom(configurations.implementation.get())
}

tasks.register<JavaExec>("tierEVerifyArtifacts") {
    group = "verification"
    description = "Verify one retained 15-fork Tier E artifact directory using Kotlin, without a container endpoint"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val artifacts = providers.gradleProperty("tierEArtifacts")
    doFirst {
        require(artifacts.isPresent) { "Pass -PtierEArtifacts=/path/to/decision-run" }
        args("verify-dir", artifacts.get())
    }
}

tasks.register<JavaExec>("tierELongMatchedDecision") {
    group = "verification"
    description = "Generate diagnostic paired Tier E CPU statistics from retained rows using Kotlin"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val input = providers.gradleProperty("tierEJsonl")
    val output = providers.gradleProperty("tierEDecisionOutput")
    doFirst {
        require(input.isPresent && output.isPresent) {
            "Pass -PtierEJsonl=/path/to/fork-results.jsonl -PtierEDecisionOutput=/path/to/decision.json"
        }
        args("summarize", input.get(), output.get())
    }
}

tasks.register<JavaExec>("tierESourceFingerprint") {
    group = "verification"
    description = "Content-address the current Tier E runtime source using Kotlin"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val repo = providers.gradleProperty("tierERepository")
    val before = providers.gradleProperty("tierEPrevious")
    doFirst {
        require(repo.isPresent) { "Pass -PtierERepository=/path/to/repository" }
        args("fingerprint", repo.get())
        if (before.isPresent) args("--verify", before.get())
    }
}

tasks.register<JavaExec>("tierEResolveConfig") {
    group = "verification"
    description = "Resolve a versioned Tier E JSON profile into a non-secret run configuration"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/smoke.json")
    val output = providers.gradleProperty("tierEConfigOutput")
    doFirst {
        require(output.isPresent) { "Pass -PtierEConfigOutput=/path/to/resolved-config.json" }
        args("config", profile.get(), output.get())
    }
}

tasks.register<JavaExec>("tierEResolveExperiment") {
    group = "verification"
    description = "Validate and resolve a versioned Tier E experiment profile"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/trustworthy.json")
    val output = providers.gradleProperty("tierEConfigOutput")
    doFirst {
        require(output.isPresent) { "Pass -PtierEConfigOutput=/path/to/resolved.json" }
        args("experiment-config", profile.get(), output.get())
    }
}

tasks.register<JavaExec>("tierETrustworthyExperiment") {
    group = "verification"
    description = "Explicit profile-selected rootful benchmark; fresh kernel evidence is never cached"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/trustworthy.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("experiment-run", profile.get(), repositoryPath, output.get())
    }
}

tasks.register<JavaExec>("tierENodeNoiseExperiment") {
    group = "verification"
    description = "Explicit profile-selected node-noise experiment; requires fresh kernel evidence"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/node-noise.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("node-noise-profile", profile.get(), repositoryPath, output.get())
    }
}

tasks.register<JavaExec>("tierECapacityExperiment") {
    group = "verification"
    description = "Explicit profile-selected capacity stages with fresh rootful Docker evidence"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/capacity.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("capacity-profile", profile.get(), repositoryPath, output.get())
    }
}

tasks.register<JavaExec>("tierERootfulPhaseProbe") {
    group = "verification"
    description = "Fresh, never-cached rootful native phase acquisition without the full profiler suite"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("rootful-phase", repositoryPath, output.get())
    }
}

tasks.register<JavaExec>("tierERootfulGate") {
    group = "verification"
    description = "Fresh rootful Docker verification with source-keyed native and Gradle build reuse"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/rootful-gate.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("rootful-gate-profile", repositoryPath, output.get(), profile.get())
    }
}

tasks.register<JavaExec>("tierEReplayPhaseEvidence") {
    group = "verification"
    description = "Recheck retained native phase samples with the unchanged historical baseline"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val samples = providers.gradleProperty("tierEArtifacts")
    val output = providers.gradleProperty("tierEOutput")
    val baselinePath = rootProject.file("profiler-native/tier-e-benchmark-baseline.env").absolutePath
    samples.orNull?.let { artifactRoot ->
        for (mode in listOf("full", "unique")) {
            for (sample in 1..3) {
                inputs.file(file("$artifactRoot/$mode-$sample.log")).withPathSensitivity(PathSensitivity.NONE)
            }
        }
        inputs.file(file(baselinePath)).withPathSensitivity(PathSensitivity.NONE)
    }
    output.orNull?.let { outputs.file(file(it)) }
    doFirst {
        require(samples.isPresent && output.isPresent) { "Pass -PtierEArtifacts=/path/to/samples -PtierEOutput=/path/to/verdict" }
        args("phase-replay", samples.get(), baselinePath, output.get())
    }
}

tasks.register<JavaExec>("tierEShortFixture") {
    group = "verification"
    description = "Explicit short Kotlin-owned Tier E split fixture using a versioned JSON profile"
    dependsOn("installDist")
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/smoke.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    if (providers.environmentVariable("MAZEWALL_DOCKER_HOST").isPresent) environment("DOCKER_HOST", "")
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("fixture-run", profile.get(), repositoryPath, output.get())
    }
}

// An explicit, intentionally non-cached measurement. It is never wired into
// test, integrationTest, check, or build: 75,000 paced operations impose a
// 60-second wall-clock floor on each of seven arms per fork.
tasks.register<JavaExec>("tierELongMatchedBenchmark") {
    group = "verification"
    description = "Explicit long Tier E seven-arm matched benchmark (defaults to 15 forks)"
    dependsOn("installDist")
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val repositoryPath = rootProject.projectDir.absolutePath
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/long-matched.json")
    val forks = providers.gradleProperty("tierEForks").orElse("15")
    val operations = providers.gradleProperty("tierEOperations").orElse("75000")
    val output =
        providers.gradleProperty("tierEOutput").orElse(
            rootProject.layout.buildDirectory
                .dir("tier-e-long-matched")
                .map { it.asFile.absolutePath },
        )
    if (providers.environmentVariable("MAZEWALL_DOCKER_HOST").isPresent) environment("DOCKER_HOST", "")
    doFirst {
        require(forks.get().toIntOrNull()?.let { it > 0 } == true) { "tierEForks must be positive" }
        require(operations.get().toIntOrNull()?.let { it >= 72 && it % 24 == 0 } == true) {
            "tierEOperations must be at least 72 and divisible by 24"
        }
        args("coverage-matrix", profile.get(), repositoryPath, output.get(), "hybrid", "1", forks.get(), operations.get())
    }
}

val tierEToolingJar =
    tasks.register<Jar>("tierEToolingJar") {
        archiveBaseName.set("tier-e-tools")
        from(toolingSourceSet.output)
    }

tasks.named<Sync>("installDist") {
    dependsOn(tierEToolingJar)
    from(tierEToolingJar) { into("lib") }
}

val generateInvocationStateBtf =
    tasks.register<GenerateInvocationStateBtf>("generateInvocationStateBtf") {
        source.set(layout.projectDirectory.file("src/main/c/btf/invocation_state.c"))
        objectFile.set(layout.buildDirectory.file("generated/btf/invocation_state.o"))
        resource.set(layout.buildDirectory.file("generated/resources/btf/invocation_state.btf"))
    }

tasks.named("processResources") {
    dependsOn(generateInvocationStateBtf)
}

// Associate integration tests with main and test to allow accessing internal members and test utilities
val kotlinExtension = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
val kotlinCompilations = kotlinExtension.target.compilations
val enforcerJar = project(":enforcer").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val platformJar = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
kotlinCompilations.matching { it.name in setOf("main", "test", "integrationTest") }.configureEach {
    compileTaskProvider.configure {
        dependsOn(enforcerJar, platformJar)
        compilerOptions.freeCompilerArgs.addAll(
            enforcerJar.zip(platformJar) { enforcer, platform ->
                listOf(
                    "-Xfriend-paths=${enforcer.asFile.absolutePath}",
                    "-Xfriend-paths=${platform.asFile.absolutePath}",
                )
            },
        )
    }
}
kotlinCompilations.named("integrationTest") {
    associateWith(kotlinCompilations.getByName("main"))
    associateWith(kotlinCompilations.getByName("test"))
}
kotlinCompilations.named("tooling") {
    associateWith(kotlinCompilations.getByName("main"))
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

fun Test.configureIntegrationSourceSet() {
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        configureIntegrationSourceSet()
        description = "Kernel tests that do not install on the JUnit worker JVM"
        useJUnitPlatform {
            excludeTags("needs-fresh-jvm")
            excludeTags("rootful-container")
        }
        dependsOn("rootfulContainerTest")
        // Kernel and process state can change without a Gradle input changing.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        forkEvery = 0
        maxParallelForks = 1
    }

// Availability is external mutable state: a previously skipped rootful test
// cannot be reused when an endpoint appears (or disappears) at the same URI.
val rootfulContainerTest =
    tasks.register<Test>("rootfulContainerTest") {
        configureIntegrationSourceSet()
        description = "Short endpoint-gated Docker/Podman rootful tests"
        dependsOn("installDist")
        useJUnitPlatform { includeTags("rootful-container") }
        // A reused Gradle daemon can retain stale DOCKER_HOST values from a
        // previous invocation. Forward the current operator selection into
        // this worker, including blanks that clear stale daemon settings.
        for (name in listOf("TIER_E_CONTAINER_ENGINE", "MAZEWALL_DOCKER_HOST", "DOCKER_HOST", "CONTAINER_HOST", "TIER_E_DOCKER_IMAGE")) {
            val selected =
                if (name == "DOCKER_HOST" && providers.environmentVariable("MAZEWALL_DOCKER_HOST").isPresent) {
                    "" // Explicit Tier E socket overrides a stale CLI default in this worker.
                } else {
                    providers.environmentVariable(name).orElse("").get()
                }
            environment(name, selected)
        }
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        maxParallelForks = 1
    }

val integrationTestFreshJvm =
    tasks.register<Test>("integrationTestFreshJvm") {
        configureIntegrationSourceSet()
        description = "Kernel tests that install seccomp/USER_NOTIF on the worker JVM"
        useJUnitPlatform {
            includeTags("needs-fresh-jvm")
        }
        forkEvery = 1
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        doFirst(io.mazewall.build.FreshJvmClassFilterAction())
    }

// Rootful Tier E fixtures run in a fresh worker and need their native paths
// forwarded explicitly; Gradle project properties are not inherited by every
// test worker automatically.
tasks.withType<Test>().configureEach {
    System.getProperty("tier.e.agent")?.let { systemProperty("tier.e.agent", it) }
    System.getProperty("tier.e.workloadClasspath")?.let { systemProperty("tier.e.workloadClasspath", it) }
}

// Fresh-JVM tests spawn a child using this task's runtime classpath. Keep the
// ordinary integration source set quiescent while that child is loading classes.
integrationTestFreshJvm.configure {
    mustRunAfter(integrationTest)
}

tasks.check {
    dependsOn(integrationTest, integrationTestFreshJvm)
}

tasks.test {
}

val plantumlConfig by configurations.creating

dependencies {
    plantumlConfig(libs.plantuml.core)
    implementation(project(":platform"))
    implementation(project(":enforcer"))
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxCoroutines)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.runner)
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
            "io.mazewall.profiler.compiler.BobCompiler*",
            "io.mazewall.profiler.engine.SyscallPathResolver*",
            "io.mazewall.profiler.engine.ProfilerSessionMachine*",
            "io.mazewall.profiler.ProfilingCoverage*",
            "io.mazewall.profiler.BillOfBehavior*",
            "io.mazewall.profiler.tierE.daemon.ControlProtocol*",
        ),
    )

    excludedClasses.set(
        setOf(
            "io.mazewall.profiler.internal.ProfilerDaemon*",
            "io.mazewall.profiler.engine.ProfilerTransport*",
            "io.mazewall.profiler.StraceProfiler*",
            "io.mazewall.profiler.ebpf.*",
        ),
    )

    targetTests.set(
        setOf(
            "io.mazewall.profiler.compiler.BobCompilerTest",
            "io.mazewall.profiler.engine.SyscallPathResolverTest",
            "io.mazewall.profiler.engine.ProfilerSessionMachineTest",
            "io.mazewall.profiler.ProfilingCoverageTest",
            "io.mazewall.profiler.ProfilerSessionApiTest",
            "io.mazewall.profiler.BillOfBehaviorTest",
            "io.mazewall.profiler.tierE.daemon.ControlProtocolTest",
        ),
    )

    jvmArgs.set(listOf("--enable-native-access=ALL-UNNAMED"))
    timeoutConstInMillis.set(2000)
    timeoutFactor.set(BigDecimal.valueOf(1.25))
    threads.set(System.getProperty("pitest.threads")?.toInt() ?: 4)

    // Host-unit floor for profile-evidence and policy-compilation behavior.
    coverageThreshold.set(93)
    mutationThreshold.set(65)
    testStrengthThreshold.set(75)
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
        exclude(methods().withName("getEntries") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("profile") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("wrap") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("toPolicy") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("toDsl") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(classes().withNameLike("*\\$*"))
        exclude(classes().withNameLike("*Kt"))
        exclude(classes().withName("io.mazewall.profiler.engine.ProfilerConstantsKt"))
        exclude(classes().withName("io.mazewall.profiler.tierE.collector.LiveEbpfCollector"))
        exclude(classes().withName("io.mazewall.profiler.collector.LiveEbpfCollector"))
        exclude(classes().withName("io.mazewall.profiler.strace.StraceProfiler"))
        exclude(classes().withNameLike("io.mazewall.profiler.tierE.engine.Bpf*Layout"))
        exclude(classes().withNameLike("io.mazewall.profiler.tierE.ringbuf.*"))
    }

    diagram {
        name("Profiler Class Diagram")
        include(packages().withName("io.mazewall.profiler"))
        writeTo(file("$rootDir/docs/diagrams/profiler_class_diagram.puml"))
        renderTo(file("$rootDir/docs/diagrams/profiler_class_diagram.svg"))
    }
}

tasks.named("generateClassDiagrams") {
    dependsOn(generateInvocationStateBtf)
    val pumlFile = file("$rootDir/docs/diagrams/profiler_class_diagram.puml")
    val svgFile = file("$rootDir/docs/diagrams/profiler_class_diagram.svg")

    doLast {
        fun cleanup(file: File) {
            if (file.exists()) {
                var content = file.readText()
                // Strip Kotlin value-class mangling hash suffix (e.g. -r9EpL9Y, -LsA-840, etc.)
                content = content.replace(Regex("([a-zA-Z0-9_]+)-[a-zA-Z0-9_-]{7,15}(?=\\b|\\(|$)"), "$1")
                // Strip any leftover internal module access flags (e.g. $io_mazewall_enforcer)
                content = content.replace(Regex("\\\$[a-zA-Z0-9_]+"), "")
                content = content.replace("set separator none\n", "")
                // Fix PlantUML SVG XML parsing error (duplicate data attribute in <g class="entity">)
                content = content.replace(Regex(" data=\"([^\"]*)\" id=\"([^\"]*)\" data=\"([^\"]*)\""), " data-name=\"$1\" id=\"$2\" data-line=\"$3\"")
                file.writeText(content)
            }
        }

        cleanup(pumlFile)
        cleanup(svgFile)
    }
}

// Diagram generation writes checked-in documentation. Keep it explicit (`generateClassDiagrams`)
// so ordinary builds remain read-only with respect to the source tree.
