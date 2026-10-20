import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            extractBtf(objectFile.get().asFile, resource.get().asFile)
        }

        private fun extractBtf(
            objectFile: java.io.File,
            resourceFile: java.io.File,
        ) {
            val image = objectFile.readBytes()
            require(image.size >= ELF64_HEADER_SIZE && image.copyOfRange(0, ELF_MAGIC.size).contentEquals(ELF_MAGIC)) {
                "BTF object is not an ELF file: $objectFile"
            }
            require(image[ELF_CLASS_OFFSET] == ELFCLASS64 && image[ELF_DATA_OFFSET] == ELFDATA2LSB) {
                "BTF object must be a little-endian ELF64 file: $objectFile"
            }
            val elf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
            val sectionHeadersOffset = checkedInt(elf.getLong(SECTION_HEADERS_OFFSET))
            val sectionHeaderSize = elf.getShort(SECTION_HEADER_SIZE_OFFSET).toInt() and UNSIGNED_SHORT_MASK
            val sectionCount = elf.getShort(SECTION_COUNT_OFFSET).toInt() and UNSIGNED_SHORT_MASK
            val namesSection = elf.getShort(SECTION_NAMES_INDEX_OFFSET).toInt() and UNSIGNED_SHORT_MASK
            require(sectionHeaderSize >= ELF64_SECTION_HEADER_SIZE && namesSection < sectionCount) { "invalid ELF section headers: $objectFile" }

            fun sectionHeader(index: Int): Int = sectionHeadersOffset + index * sectionHeaderSize

            fun sectionOffset(header: Int): Int = checkedInt(elf.getLong(header + SECTION_OFFSET_OFFSET))

            fun sectionSize(header: Int): Int = checkedInt(elf.getLong(header + SECTION_SIZE_OFFSET))

            fun checkedRange(
                offset: Int,
                size: Int,
            ): IntRange {
                require(offset >= 0 && size >= 0 && offset <= image.size - size) { "invalid ELF section range: $objectFile" }
                return offset until offset + size
            }

            val namesHeader = sectionHeader(namesSection)
            val names = checkedRange(sectionOffset(namesHeader), sectionSize(namesHeader))

            fun sectionName(header: Int): String {
                val start = names.first + elf.getInt(header)
                require(start in names) { "invalid ELF section name: $objectFile" }
                val end =
                    generateSequence(start) { index -> (index + 1).takeIf { it in names } }
                        .first { image[it] == 0.toByte() }
                return image.copyOfRange(start, end).decodeToString()
            }

            val btfHeader =
                (0 until sectionCount)
                    .map(::sectionHeader)
                    .firstOrNull { sectionName(it) == BTF_SECTION }
                    ?: error("ELF BTF section is missing: $objectFile")
            val btf = checkedRange(sectionOffset(btfHeader), sectionSize(btfHeader))
            resourceFile.writeBytes(image.copyOfRange(btf.first, btf.last + 1))
        }

        private fun checkedInt(value: Long): Int {
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "ELF offset exceeds supported range: $value" }
            return value.toInt()
        }

        private companion object {
            val ELF_MAGIC: ByteArray = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
            const val ELF_CLASS_OFFSET: Int = 4
            const val ELF_DATA_OFFSET: Int = 5
            const val ELFCLASS64: Byte = 2
            const val ELFDATA2LSB: Byte = 1
            const val ELF64_HEADER_SIZE: Int = 64
            const val ELF64_SECTION_HEADER_SIZE: Int = 64
            const val SECTION_HEADERS_OFFSET: Int = 40
            const val SECTION_HEADER_SIZE_OFFSET: Int = 58
            const val SECTION_COUNT_OFFSET: Int = 60
            const val SECTION_NAMES_INDEX_OFFSET: Int = 62
            const val SECTION_OFFSET_OFFSET: Int = 24
            const val SECTION_SIZE_OFFSET: Int = 32
            const val UNSIGNED_SHORT_MASK: Int = 0xffff
            const val BTF_SECTION: String = ".BTF"
        }
    }

plugins {
    id("mazewall.quality-conventions")
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

// Native HotSpot assets are explicit operator tasks, never prerequisites of JVM builds.
val hotSpotToolsBinary = layout.projectDirectory.file("hotspot-agentless/build/mw-collector-tools")
val buildHotSpotTools =
    tasks.register<Exec>("buildHotSpotTools") {
        group = "distribution"
        description = "Compile the pinned native asset maintenance tool from its declared sources."
        workingDir(layout.projectDirectory.dir("hotspot-agentless"))
        inputs.files(fileTree("hotspot-agentless/cmd/mw-collector-tools"), fileTree("hotspot-agentless/internal/tooling"))
        inputs.files("hotspot-agentless/go.mod", "hotspot-agentless/go.sum", "hotspot-agentless/dependencies.lock.json")
        inputs.property(
            "goCompilerVersion",
            providers
                .exec { commandLine("go", "version") }
                .standardOutput.asText
                .map { it.trim() },
        )
        outputs.file(hotSpotToolsBinary)
        environment("CGO_ENABLED", "0")
        environment("GOOS", "linux")
        environment("GOARCH", "amd64")
        commandLine("go", "build", "-trimpath", "-buildvcs=false", "-o", "build/mw-collector-tools", "./cmd/mw-collector-tools")
    }

tasks.register<Exec>("buildHotSpotAssets") {
    group = "distribution"
    description = "Build the pinned external HotSpot collector and verified local OCI bundle (requires Go/Docker)."
    workingDir(layout.projectDirectory.dir("hotspot-agentless"))
    commandLine("make", "bundle")
}

tasks.register<Exec>("verifyHotSpotAssets") {
    group = "verification"
    description = "Verify an existing local HotSpot bundle without constructing native inputs."
    dependsOn(buildHotSpotTools)
    workingDir(layout.projectDirectory.dir("hotspot-agentless"))
    commandLine(hotSpotToolsBinary.asFile.absolutePath, "verify", "--bundle", "build/assets")
    mustRunAfter("buildHotSpotAssets")
}

tasks.register<Exec>("distHotSpotAssets") {
    group = "distribution"
    description = "Package verified local HotSpot assets, selected dependency notices and corresponding native source."
    dependsOn("verifyHotSpotAssets")
    workingDir(layout.projectDirectory.dir("hotspot-agentless"))
    commandLine(
        hotSpotToolsBinary.asFile.absolutePath,
        "release",
        "--bundle",
        "build/assets",
        "--output",
        "build/distributions/mazewall-hotspot-${project.version}-linux-amd64.tar.gz",
        "--library-version",
        project.version.toString(),
    )
}

tasks.register<Exec>("installHotSpotAssets") {
    group = "distribution"
    description = "Verify/install an existing bundle into a new explicit -PhotSpotAssetsDirectory path."
    workingDir(layout.projectDirectory.dir("hotspot-agentless"))
    dependsOn(buildHotSpotTools)
    mustRunAfter("buildHotSpotAssets", "verifyHotSpotAssets")
    val destination = providers.gradleProperty("hotSpotAssetsDirectory")
    executable = hotSpotToolsBinary.asFile.absolutePath
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("install", "--bundle", "build/assets", "--output", destination.get())
        },
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
    providers.gradleProperty("tierEEndpoint").orNull?.let { systemProperty("mazewall.tier-e.endpoint", it) }
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/trustworthy.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    val nativeBuild = tasks.named<io.mazewall.build.TierENativeBuildTask>("tierETrustworthyNativeBuild")
    dependsOn(nativeBuild)
    val nativePackage = nativeBuild.flatMap { it.artifactDirectory }.map { it.asFile.absolutePath }
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("experiment-run", profile.get(), repositoryPath, output.get(), nativePackage.get())
    }
}

tasks.register<JavaExec>("tierENodeNoiseExperiment") {
    group = "verification"
    description = "Explicit profile-selected node-noise experiment; requires fresh kernel evidence"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    providers.gradleProperty("tierEEndpoint").orNull?.let { systemProperty("mazewall.tier-e.endpoint", it) }
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/node-noise.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    val nativeBuild = tasks.named<io.mazewall.build.TierENativeBuildTask>("tierETrustworthyNativeBuild")
    dependsOn(nativeBuild)
    val nativePackage = nativeBuild.flatMap { it.artifactDirectory }.map { it.asFile.absolutePath }
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("node-noise-profile", profile.get(), repositoryPath, output.get(), nativePackage.get())
    }
}

tasks.register<JavaExec>("tierENodeNoiseQualification") {
    group = "verification"
    description = "Explicit five-pair fail-closed control/noise comparison; both 10,000-operation modes must be exact"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    providers.gradleProperty("tierEEndpoint").orNull?.let { systemProperty("mazewall.tier-e.endpoint", it) }
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/node-noise.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    val nativeBuild = tasks.named<io.mazewall.build.TierENativeBuildTask>("tierETrustworthyNativeBuild")
    dependsOn(nativeBuild)
    val nativePackage = nativeBuild.flatMap { it.artifactDirectory }.map { it.asFile.absolutePath }
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("node-noise-qualification", profile.get(), repositoryPath, output.get(), nativePackage.get())
    }
}

tasks.register<JavaExec>("tierECapacityExperiment") {
    group = "verification"
    description = "Explicit profile-selected capacity stages with fresh rootful Docker evidence"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    providers.gradleProperty("tierEEndpoint").orNull?.let { systemProperty("mazewall.tier-e.endpoint", it) }
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/capacity.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    val nativeBuild = tasks.named<io.mazewall.build.TierENativeBuildTask>("tierETrustworthyNativeBuild")
    dependsOn(nativeBuild)
    val nativePackage = nativeBuild.flatMap { it.artifactDirectory }.map { it.asFile.absolutePath }
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("capacity-profile", profile.get(), repositoryPath, output.get(), nativePackage.get())
    }
}

tasks.register<JavaExec>("tierERootfulPhaseProbe") {
    group = "verification"
    description = "Fresh, never-cached rootful native phase acquisition without the full profiler suite"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    providers.gradleProperty("tierEEndpoint").orNull?.let { systemProperty("mazewall.tier-e.endpoint", it) }
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/rootful-gate.json")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    val nativeBuild = tasks.named<io.mazewall.build.TierENativeBuildTask>("tierENativeBuild")
    dependsOn(nativeBuild)
    val nativePackage = nativeBuild.flatMap { it.artifactDirectory }.map { it.asFile.absolutePath }
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("rootful-phase-profile", repositoryPath, output.get(), profile.get(), nativePackage.get())
    }
}

tasks.register<JavaExec>("tierENativeTimingDiagnostic") {
    group = "verification"
    description = "Non-qualifying, fixed-budget three-arm native capture timing diagnostic"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val output = providers.gradleProperty("tierEOutput")
    val readiness = providers.gradleProperty("tierEReadiness")
    val repositoryPath = rootProject.projectDir.absolutePath
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        require(readiness.isPresent) { "Pass -PtierEReadiness=/path/to/complete-readiness" }
        args("native-timing-diagnostic", repositoryPath, output.get(), readiness.get())
    }
}

tasks.register<JavaExec>("tierENativeTimingReadiness") {
    group = "verification"
    description = "Independent non-qualifying six-fork native timing readiness gate"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    val output = providers.gradleProperty("tierEOutput")
    val repositoryPath = rootProject.projectDir.absolutePath
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-readiness-artifacts" }
        args("native-timing-readiness", repositoryPath, output.get())
    }
}

tasks.register<JavaExec>("tierERootfulGate") {
    group = "verification"
    description = "Fresh rootful Docker verification with source-keyed native and Gradle build reuse"
    classpath = toolingSourceSet.runtimeClasspath
    workingDir(rootProject.projectDir)
    mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    providers.gradleProperty("tierEEndpoint").orNull?.let { systemProperty("mazewall.tier-e.endpoint", it) }
    val profile = providers.gradleProperty("tierEProfile").orElse("config/testing/tier-e/rootful-gate.json")
    val output = providers.gradleProperty("tierEOutput")
    val phase = providers.gradleProperty("tierEPhaseArtifacts")
    val repositoryPath = rootProject.projectDir.absolutePath
    val nativeBuild = tasks.named<io.mazewall.build.TierENativeBuildTask>("tierENativeBuild")
    dependsOn(nativeBuild)
    val nativePackage = nativeBuild.flatMap { it.artifactDirectory }.map { it.asFile.absolutePath }
    doFirst {
        require(output.isPresent) { "Pass -PtierEOutput=/path/to/fresh-artifacts" }
        args("rootful-gate-profile", repositoryPath, output.get(), profile.get(), phase.orNull ?: "--no-phase", nativePackage.get())
    }
}

tasks.register<io.mazewall.build.TierEPhaseReplayTask>("tierEReplayPhaseEvidence") {
    group = "verification"
    description = "Cacheable analysis of six retained native phase samples; never acquires fresh measurements"
    val samples = providers.gradleProperty("tierEArtifacts")
    val output = providers.gradleProperty("tierEOutput")
    samples.orNull?.let { artifactRoot ->
        sampleDirectory.fileValue(file(artifactRoot))
        for (mode in listOf("full", "unique")) {
            for (sample in 1..3) {
                this.samples.from(file("$artifactRoot/$mode-$sample.log"))
            }
        }
    }
    baseline.fileValue(rootProject.file("profiler-native/tier-e-benchmark-baseline.env"))
    verifierClasspath.from(toolingSourceSet.runtimeClasspath)
    verifierMainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
    contractVersion.set("2")
    output.orNull?.let { reportDirectory.fileValue(file(it)) }
}

fun registerTierENativeBuild(
    taskName: String,
    defaultBuildType: String,
    defaultUsdt: String,
    outputName: String,
) = tasks.register<io.mazewall.build.TierENativeBuildTask>(taskName) {
    group = "verification"
    description = "Cache only native binary and workload build outputs, never CTest execution"
    val docker = providers.gradleProperty("tierEDockerCli").orElse("/usr/bin/docker")
    val endpoint =
        providers
            .gradleProperty("tierEEndpoint")
            .orElse(providers.environmentVariable("MAZEWALL_DOCKER_HOST"))
            .orElse("unix:///var/run/docker.sock")
    val defaultProfile = if (defaultBuildType == "Debug") "config/testing/tier-e/rootful-gate.json" else "config/testing/tier-e/trustworthy.json"
    val profileFile = rootProject.file(providers.gradleProperty("tierEProfile").orElse(defaultProfile).get())
    val profiledImage =
        providers.fileContents(layout.file(providers.provider { profileFile })).asText.map { text ->
            val container =
                (groovy.json.JsonSlurper().parseText(text) as? Map<*, *>)?.get("container") as? Map<*, *>
                    ?: error("Tier E native build profile lacks a container")
            val selected = container["image"] as? String ?: error("Tier E native build profile lacks an image")
            require(selected.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]+"))) { "Invalid Tier E native build image" }
            selected
        }
    val image =
        profiledImage.zip(providers.gradleProperty("tierENativeImage").orElse(profiledImage)) { selected, requested ->
            require(requested == selected) { "Native image override conflicts with selected Tier E experiment profile" }
            selected
        }
    val inspectedImage =
        providers
            .exec {
                commandLine(docker.get(), "image", "inspect", image.get(), "--format", "{{.Id}}")
                environment("DOCKER_HOST", endpoint.get())
            }.standardOutput.asText
            .map(String::trim)

    fun dockerTool(
        tool: String,
        argument: String,
    ) = providers.exec {
        commandLine(docker.get(), "run", "--rm", "--entrypoint", tool, inspectedImage.get(), argument)
        environment("DOCKER_HOST", endpoint.get())
    }
    nativeDirectory.fileValue(rootProject.file("profiler-native"))
    nativeSources.from(
        rootProject.fileTree("profiler-native") {
            include("CMakeLists.txt", "src/**", "include/**", "tests/**")
            exclude("build/**")
        },
    )
    dockerExecutable.fileValue(file(docker.get()))
    dockerEndpoint.set(endpoint)
    imageDigest.set(inspectedImage)
    compilerVersion.set(dockerTool("c++", "--version").standardOutput.asText.map { it.lineSequence().first().trim() })
    javaVersion.set(dockerTool("java", "-version").standardError.asText.map(String::trim))
    cmakeVersion.set(dockerTool("cmake", "--version").standardOutput.asText.map { it.lineSequence().first().trim() })
    targetArchitecture.set(
        providers
            .exec {
                commandLine(docker.get(), "image", "inspect", inspectedImage.get(), "--format", "{{.Architecture}}")
                environment("DOCKER_HOST", endpoint.get())
            }.standardOutput.asText
            .map(String::trim),
    )
    buildType.set(providers.gradleProperty("tierENativeBuildType").orElse(defaultBuildType))
    usdtEnabled.set(
        providers.gradleProperty("tierENativeUsdt").orElse(defaultUsdt).map { value ->
            value.toBooleanStrictOrNull() ?: error("tierENativeUsdt must be true or false")
        },
    )
    artifactDirectory.set(layout.buildDirectory.dir(outputName))
}

registerTierENativeBuild("tierENativeBuild", "Debug", "false", "tier-e-native-artifacts")
registerTierENativeBuild(
    "tierETrustworthyNativeBuild",
    "Release",
    (System.getProperty("os.arch") in setOf("amd64", "x86_64")).toString(),
    "tier-e-trustworthy-native-artifacts",
)

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

val tierEToolsStartScripts =
    tasks.register<org.gradle.jvm.application.tasks.CreateStartScripts>("tierEToolsStartScripts") {
        applicationName = "tier-e-tools"
        mainClass.set("io.mazewall.profiler.tierE.tools.TierEToolsMainKt")
        classpath = files(tierEToolingJar, tasks.named("jar"), configurations.runtimeClasspath)
        defaultJvmOpts = application.applicationDefaultJvmArgs
        outputDir =
            layout.buildDirectory
                .dir("tier-e-tools-scripts")
                .get()
                .asFile
    }

distributions.main {
    contents {
        from(tierEToolingJar) { into("lib") }
        from(tierEToolsStartScripts) {
            into("bin")
            filePermissions { unix("rwxr-xr-x") }
        }
    }
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
// Friend paths name packaged artifacts. Resolve the same artifacts instead of Java-library
// classes-directory variants, which Kotlin treats as a different visibility boundary.
configurations.matching { it.name == "compileClasspath" || it.name.endsWith("CompileClasspath") }.configureEach {
    attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
}
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
    associateWith(kotlinCompilations.getByName("tooling"))
}
kotlinCompilations.named("test") {
    associateWith(kotlinCompilations.getByName("tooling"))
}
kotlinCompilations.named("tooling") {
    associateWith(kotlinCompilations.getByName("main"))
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named("integrationTestRuntimeOnly") {
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
    dependsOn("installDist")
    inputs
        .dir(layout.buildDirectory.dir("install/tier-e-daemon"))
        .withPropertyName("installedTierEDistribution")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val plantumlConfig = configurations.create("plantumlConfig")

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

    timeoutConstInMillis.set(2000)
    timeoutFactor.set(BigDecimal.valueOf(1.25))

    // Host-unit floor for profile-evidence and policy-compilation behavior.
    coverageThreshold.set(93)
    mutationThreshold.set(65)
    testStrengthThreshold.set(75)
}

apply(from = rootProject.file("gradle/pitest-limits.gradle"))

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
