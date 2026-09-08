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
    id("mazewall.publishing-conventions")
    application
    id("info.solidsoft.pitest")
    alias(libs.plugins.plantuml)
    alias(libs.plugins.kotlinPluginSerialization)
}

application {
    mainClass.set("io.mazewall.profiler.tierE.daemon.TierEKotlinDaemonKt")
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
    }
    create("integrationTest") {
        java.srcDir(rootProject.file("src/sharedTest/kotlin"))
        compileClasspath += main.get().output + test.get().output
        runtimeClasspath += main.get().output + test.get().output
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
        }
        forkEvery = 0
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
        doFirst(io.mazewall.build.FreshJvmClassFilterAction())
    }

tasks.test {
}

val plantumlConfig = configurations.create("plantumlConfig")

dependencies {
    plantumlConfig(libs.plantuml.core)
    api(project(":platform"))
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
    threads.set(providers.gradleProperty("mazewall.pitest.threads").orElse("2").map(String::toInt))

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
            hidePackages()
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
        // Exclude constant/data-heavy/mapper noise
        exclude(classes().withName("io.mazewall.profiler.engine.ProfilerConstantsKt"))
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
                // Fix PlantUML SVG XML parsing error (duplicate data attribute in <g class="entity">)
                content = content.replace(Regex(" data=\"([^\"]*)\" id=\"([^\"]*)\" data=\"([^\"]*)\""), " data-name=\"$1\" id=\"$2\" data-line=\"$3\"")
                file.writeText(content)
            }
        }

        cleanup(pumlFile)
        cleanup(svgFile)
    }
}
