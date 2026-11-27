import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import java.io.File
import java.lang.module.ModuleFinder
import java.math.BigDecimal
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("mazewall.quality-conventions")
    id("info.solidsoft.pitest")
}

base {
    archivesName.set("mazewall-lockdown")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "mazewall-lockdown"
            from(components["java"])
            pom {
                name.set("Mazewall Lockdown")
                description.set(
                    "Process-wide external-program execution lockdown for Linux JVM services via Seccomp-BPF and JDK FFM.",
                )
                url.set("https://github.com/Pilleo/mazewall")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("Pilleo")
                        name.set("Mazewall Contributors")
                        url.set("https://github.com/Pilleo/mazewall")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Pilleo/mazewall.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Pilleo/mazewall.git")
                    url.set("https://github.com/Pilleo/mazewall")
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(25)
    explicitApi()
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}

val compileKotlinTask = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")

tasks.named<JavaCompile>("compileJava") {
    dependsOn(compileKotlinTask)
    modularity.inferModulePath.set(true)
    val kotlinClassesDir = compileKotlinTask.flatMap { it.destinationDirectory }
    options.compilerArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--patch-module",
                "io.mazewall.lockdown=${kotlinClassesDir.get().asFile.absolutePath}",
            )
        },
    )
}

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    manifest {
        attributes("Automatic-Module-Name" to "io.mazewall.lockdown")
    }
}

tasks.named<Javadoc>("javadoc") {
    exclude("module-info.java")
}

@CacheableTask
abstract class PrepareLockdownCoreSources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val platformSources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val enforcerSources: ConfigurableFileCollection

    @get:Internal
    abstract val platformRoot: DirectoryProperty

    @get:Internal
    abstract val enforcerRoot: DirectoryProperty

    @get:Input
    abstract val platformFiles: ListProperty<String>

    @get:Input
    abstract val enforcerFiles: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        copyAndSanitize(platformRoot.get().asFile, platformFiles.get(), out, isValidationSource = { false })
        copyAndSanitize(
            enforcerRoot.get().asFile,
            enforcerFiles.get(),
            out,
            isValidationSource = { it == "io/mazewall/enforcer/diagnostics/Validation.kt" },
        )
    }

    private fun copyAndSanitize(
        sourceRoot: File,
        relativePaths: List<String>,
        outRoot: File,
        isValidationSource: (String) -> Boolean,
    ) {
        val seen = HashSet<String>()
        for (rel in relativePaths) {
            check(seen.add(rel)) { "Duplicate lockdown core file entry: $rel" }
            val sourceFile = sourceRoot.resolve(rel)
            check(sourceFile.isFile) { "Missing allowlisted lockdown core file: ${sourceFile.absolutePath}" }
            val transformed =
                transformCoreSource(
                    sourceFile.readText(Charsets.UTF_8),
                    rewriteValidation = isValidationSource(rel),
                )
            val destRel =
                "io/mazewall/lockdown/internal/" +
                    rel.removePrefix("io/mazewall/").replace("platform/native/", "platform/sys/")
            val destFile = outRoot.resolve(destRel)
            destFile.parentFile.mkdirs()
            destFile.writeText(transformed, Charsets.UTF_8)
        }
    }

    private fun transformCoreSource(
        raw: String,
        rewriteValidation: Boolean,
    ): String {
        val withSeam =
            if (rewriteValidation) {
                raw
                    .replace("import io.mazewall.enforcer.api.Platform", "import io.mazewall.seccomp.SeccompPlatformProbe")
                    .replace("Platform.validateLinux()", "SeccompPlatformProbe.validateLinux()")
            } else {
                raw
            }
        val filteredLines =
            withSeam
                .lineSequence()
                .filterNot { line ->
                    val trimmed = line.trim()
                    trimmed == "import io.mazewall.platform.process.*" || trimmed == "import io.mazewall.enforcer.*"
                }.map { line ->
                    if (line.startsWith("public ")) {
                        "internal " + line.removePrefix("public ")
                    } else {
                        line
                    }
                }
        val rewritten = filteredLines.joinToString("\n") + if (withSeam.endsWith("\n")) "\n" else ""
        // Order matters: relocate io.mazewall.platform.native before io.mazewall.platform
        // so Java keyword "native" is removed from JPMS package names without double-prefixing.
        return rewritten
            .replace("io.mazewall.platform.native", "io.mazewall.lockdown.internal.platform.sys")
            .replace("io.mazewall.ffi", "io.mazewall.lockdown.internal.ffi")
            .replace("io.mazewall.platform", "io.mazewall.lockdown.internal.platform")
            .replace("io.mazewall.seccomp", "io.mazewall.lockdown.internal.seccomp")
            .replace("io.mazewall.enforcer", "io.mazewall.lockdown.internal.enforcer")
    }
}

val lockdownPlatformFiles =
    listOf(
        "io/mazewall/ffi/IoctlCommand.kt",
        "io/mazewall/ffi/LayoutValidator.kt",
        "io/mazewall/ffi/Layouts.kt",
        "io/mazewall/ffi/NativeConstants.kt",
        "io/mazewall/ffi/internal/NativeTransfer.kt",
        "io/mazewall/ffi/internal/RawNativeTransfer.kt",
        "io/mazewall/ffi/internal/RealNativeEngine.kt",
        "io/mazewall/ffi/internal/SyscallInvoker.kt",
        "io/mazewall/ffi/memory/ManagedSegment.kt",
        "io/mazewall/ffi/memory/MemoryWrappers.kt",
        "io/mazewall/ffi/memory/NativeArena.kt",
        "io/mazewall/ffi/memory/NativeScope.kt",
        "io/mazewall/platform/fd/FileDescriptor.kt",
        "io/mazewall/platform/fd/NativeCloseFailure.kt",
        "io/mazewall/platform/fd/NativeDescriptorReceipt.kt",
        "io/mazewall/platform/lifecycle/MazewallInternal.kt",
        "io/mazewall/platform/lifecycle/SandboxedPath.kt",
        "io/mazewall/platform/lifecycle/ThreadLocalDelegate.kt",
        "io/mazewall/platform/lifecycle/valueClasses.kt",
        "io/mazewall/platform/native/LinuxNative.kt",
        "io/mazewall/platform/native/NativeBpf.kt",
        "io/mazewall/platform/native/NativeEngine.kt",
        "io/mazewall/platform/native/NativeMapping.kt",
        "io/mazewall/platform/native/NativeStackContexts.kt",
        "io/mazewall/platform/syscall/Arch.kt",
        "io/mazewall/platform/syscall/ErrnoMapping.kt",
        "io/mazewall/platform/syscall/NativeArg.kt",
        "io/mazewall/platform/syscall/PrctlCommand.kt",
        "io/mazewall/platform/syscall/SeccompAction.kt",
        "io/mazewall/platform/syscall/Syscall.kt",
        "io/mazewall/platform/syscall/SyscallNumberResolver.kt",
        "io/mazewall/seccomp/BpfInstruction.kt",
        "io/mazewall/seccomp/BpfSimulator.kt",
    )

val lockdownEnforcerFiles =
    listOf(
        "io/mazewall/enforcer/api/CompiledSandbox.kt",
        "io/mazewall/enforcer/api/PolicyArgumentRules.kt",
        "io/mazewall/enforcer/api/PolicyBuilder.kt",
        "io/mazewall/enforcer/api/PolicyCompilationCache.kt",
        "io/mazewall/enforcer/api/PolicyDefinition.kt",
        "io/mazewall/enforcer/api/PolicyScope.kt",
        "io/mazewall/enforcer/api/UnsupportedKernelFeatureException.kt",
        "io/mazewall/enforcer/diagnostics/MazewallEvents.kt",
        "io/mazewall/enforcer/diagnostics/Validation.kt",
        "io/mazewall/enforcer/engine/FilterInstallationPlanner.kt",
        "io/mazewall/enforcer/internal/seccomp/BpfFilter.kt",
        "io/mazewall/enforcer/state/ContainerState.kt",
        "io/mazewall/enforcer/state/ContainmentRegistryEffect.kt",
        "io/mazewall/enforcer/state/ContainmentStateRegistry.kt",
        "io/mazewall/enforcer/state/InstallationEvidence.kt",
        "io/mazewall/ffi/ScopedBpfProgram.kt",
        "io/mazewall/seccomp/BpfBuilder.kt",
        "io/mazewall/seccomp/BpfInspection.kt",
        "io/mazewall/seccomp/BpfProgram.kt",
        "io/mazewall/seccomp/BpfStaticVerifier.kt",
        "io/mazewall/seccomp/InstallSelfVerifier.kt",
        "io/mazewall/seccomp/ProcessFilterInstaller.kt",
        "io/mazewall/seccomp/PureJavaBpfEngine.kt",
        "io/mazewall/seccomp/SeccompEngine.kt",
        "io/mazewall/seccomp/SeccompInstallationState.kt",
        "io/mazewall/seccomp/SeccompPlatformProbe.kt",
        "io/mazewall/seccomp/SelfVerificationPlan.kt",
        "io/mazewall/seccomp/SyscallInspectionPipeline.kt",
        "io/mazewall/seccomp/SyscallInspector.kt",
    )

val platformCoreDir = rootProject.layout.projectDirectory.dir("platform/src/main/kotlin")
val enforcerCoreDir = rootProject.layout.projectDirectory.dir("enforcer/src/main/kotlin")

val prepareLockdownCoreSources =
    tasks.register<PrepareLockdownCoreSources>("prepareLockdownCoreSources") {
        group = "build"
        description = "Copies and internalizes the curated seccomp-only core subset from :platform and :enforcer."
        platformRoot.set(platformCoreDir)
        enforcerRoot.set(enforcerCoreDir)
        platformFiles.set(lockdownPlatformFiles)
        enforcerFiles.set(lockdownEnforcerFiles)
        platformSources.from(lockdownPlatformFiles.map { platformCoreDir.file(it) })
        enforcerSources.from(lockdownEnforcerFiles.map { enforcerCoreDir.file(it) })
        outputDir.set(layout.buildDirectory.dir("generated/lockdown-core/kotlin"))
    }

kotlin.sourceSets.named("main") {
    kotlin.srcDir(prepareLockdownCoreSources.flatMap { it.outputDir })
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

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    dependsOn(prepareLockdownCoreSources)
    exclude(
        "**/generated/**",
        "io/mazewall/lockdown/internal/**",
    )
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter {
        exclude { element -> element.file.invariantSeparatorsPath.contains("/build/generated/") }
    }
}

tasks.matching { it.name.contains("ktlint", ignoreCase = true) || it.name == "sourcesJar" }.configureEach {
    dependsOn(prepareLockdownCoreSources)
}

fun Test.configureLockdownIntegrationPrerequisites() {
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
}

val lockdownJarTask = tasks.named<org.gradle.jvm.tasks.Jar>("jar")
val lockdownJarFile = lockdownJarTask.flatMap { it.archiveFile }

abstract class LockdownJarArgumentProvider : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockdownJar: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf("-Dmazewall.lockdown.jar=${lockdownJar.get().asFile.absolutePath}")
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        configureLockdownIntegrationPrerequisites()
        description = "Host-safe/non-mutating integration tests for :lockdown."
        useJUnitPlatform {
            excludeTags("needs-fresh-jvm")
        }
        forkEvery = 0
        maxParallelForks = 1
    }

val integrationTestFreshJvm =
    tasks.register<Test>("integrationTestFreshJvm") {
        configureLockdownIntegrationPrerequisites()
        description = "Kernel tests that install process-wide lockdown filters on the worker JVM."
        dependsOn(lockdownJarTask)
        jvmArgumentProviders.add(
            objects.newInstance<LockdownJarArgumentProvider>().apply {
                lockdownJar.set(lockdownJarFile)
            },
        )
        mustRunAfter(integrationTest)
        useJUnitPlatform {
            includeTags("needs-fresh-jvm")
        }
        forkEvery = 1
        doFirst(io.mazewall.build.FreshJvmClassFilterAction())
    }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(setOf("io.mazewall.lockdown.*"))
    excludedClasses.set(setOf("io.mazewall.lockdown.internal.*"))
    targetTests.set(setOf("io.mazewall.lockdown.*"))
    timeoutConstInMillis.set(2000)
    timeoutFactor.set(BigDecimal.valueOf(1.25))
}

apply(from = rootProject.file("gradle/pitest-limits.gradle"))

@DisableCachingByDefault(because = "Verifies packaged lockdown JAR contents and generated Maven POM")
abstract class VerifyLockdownPublication : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jarFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pomFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourcesJarFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javadocJarFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val archive = jarFile.get().asFile
        val entries: Set<String>
        val automaticModuleName: String?
        JarFile(archive).use { jar ->
            val attrs = jar.manifest?.mainAttributes
            automaticModuleName = attrs?.getValue("Automatic-Module-Name")
            val agentHooks =
                listOf("Premain-Class", "Agent-Class", "Launcher-Agent-Class").filter { attrs?.getValue(it) != null }
            check(agentHooks.isEmpty()) {
                "Unexpected Java agent manifest attributes in ${archive.name}: $agentHooks"
            }
            entries =
                jar
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toSet()
        }
        check(automaticModuleName == "io.mazewall.lockdown") {
            "Expected Automatic-Module-Name io.mazewall.lockdown in ${archive.name}, was $automaticModuleName"
        }
        check("module-info.class" in entries) {
            "Missing JPMS descriptor module-info.class in ${archive.name}"
        }
        REQUIRED_PUBLIC_CLASSES.forEach { required ->
            check(required in entries) { "Missing required public class in ${archive.name}: $required" }
        }
        val classViolations =
            entries
                .filter { it.endsWith(".class") && it != "module-info.class" }
                .mapNotNull { entry ->
                    val forbiddenPrefix = FORBIDDEN_PACKAGE_PREFIXES.firstOrNull { entry.startsWith(it) }
                    val simpleName = entry.substringAfterLast('/')
                    val topLevelType = simpleName.removeSuffix(".class").substringBefore('$')
                    val isDirectExportedPackage =
                        entry.startsWith("io/mazewall/lockdown/") &&
                            !entry.startsWith("io/mazewall/lockdown/internal/")
                    val hasDisallowedExportedMember =
                        isDirectExportedPackage &&
                            (entry.removePrefix("io/mazewall/lockdown/").contains('/') || topLevelType !in ALLOWED_PUBLIC_TOP_LEVEL_TYPES)
                    when {
                        forbiddenPrefix != null -> "Forbidden package class in ${archive.name}: $entry"
                        simpleName in FORBIDDEN_SIMPLE_NAMES -> "Forbidden class in ${archive.name}: $entry"
                        ALLOWLISTED_PACKAGE_PREFIXES.none { entry.startsWith(it) } ->
                            "Non-allowlisted class in ${archive.name}: $entry"
                        hasDisallowedExportedMember ->
                            "Unexpected non-public-API class in exported package ${archive.name}: $entry"
                        else -> null
                    }
                }
        check(classViolations.isEmpty()) {
            "Publication allowlist check failed:\n${classViolations.joinToString("\n")}"
        }

        val moduleRefs = ModuleFinder.of(archive.toPath()).findAll()
        check(moduleRefs.size == 1) {
            "Expected exactly 1 JPMS module in ${archive.name}, found ${moduleRefs.size}"
        }
        val descriptor = moduleRefs.single().descriptor()
        check(descriptor.name() == "io.mazewall.lockdown") {
            "Unexpected JPMS module name in ${archive.name}: ${descriptor.name()}"
        }
        val exports = descriptor.exports().map { it.source() }
        check(exports == listOf("io.mazewall.lockdown")) {
            "Unexpected JPMS exports in ${archive.name}: $exports"
        }
        val classPackages =
            entries
                .asSequence()
                .filter { it.endsWith(".class") && it != "module-info.class" }
                .map { it.substringBeforeLast('/').replace('/', '.') }
                .toSet()
        val missingFromModule = classPackages - descriptor.packages()
        check(missingFromModule.isEmpty()) {
            "JPMS ModuleFinder omitted packages from ${archive.name}: $missingFromModule"
        }

        val sourcesArchive = sourcesJarFile.get().asFile
        check(sourcesArchive.isFile && sourcesArchive.length() > 0L) {
            "Missing or empty sources JAR: $sourcesArchive"
        }
        JarFile(sourcesArchive).use { sources ->
            check(sources.getJarEntry("io/mazewall/lockdown/Mazewall.kt") != null) {
                "Sources JAR is missing io/mazewall/lockdown/Mazewall.kt"
            }
        }
        val javadocArchive = javadocJarFile.get().asFile
        check(javadocArchive.isFile && javadocArchive.length() > 0L) {
            "Missing or empty javadoc JAR: $javadocArchive"
        }

        val pom = pomFile.get().asFile
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        val builder = factory.newDocumentBuilder()
        val root = builder.parse(pom).documentElement
        check(firstChildText(root, "groupId") == "io.mazewall") { "Unexpected POM groupId in $pom" }
        check(firstChildText(root, "artifactId") == "mazewall-lockdown") { "Unexpected POM artifactId in $pom" }
        val pomText = pom.readText(Charsets.UTF_8)
        check(pomText.contains("https://www.apache.org/licenses/LICENSE-2.0.txt")) {
            "Missing Apache-2.0 license URL in $pom"
        }
        check(pomText.contains("<scm>") && pomText.contains("github.com/Pilleo/mazewall")) {
            "Missing <scm> metadata in $pom"
        }

        val dependencyNodes = root.getElementsByTagName("dependency")
        val dependencies =
            (0 until dependencyNodes.length).map { idx ->
                val element = dependencyNodes.item(idx) as Element
                "${firstChildText(element, "groupId")}:${firstChildText(element, "artifactId")}"
            }
        check(dependencies == listOf("org.jetbrains.kotlin:kotlin-stdlib")) {
            "Unexpected dependencies in mazewall-lockdown POM: $dependencies"
        }
    }

    private fun firstChildText(
        parent: Element,
        tag: String,
    ): String? =
        (0 until parent.childNodes.length)
            .asSequence()
            .map { parent.childNodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.localName == tag || it.tagName == tag }
            ?.textContent
            ?.trim()

    private companion object {
        val REQUIRED_PUBLIC_CLASSES =
            listOf(
                "io/mazewall/lockdown/LockdownPreset.class",
                "io/mazewall/lockdown/LockdownSyscall.class",
                "io/mazewall/lockdown/Mazewall.class",
                "io/mazewall/lockdown/MazewallInstallationException.class",
                "io/mazewall/lockdown/ProcessInstallation.class",
                "io/mazewall/lockdown/ProcessPolicy.class",
                "io/mazewall/lockdown/RuntimeProfile.class",
            )

        val ALLOWED_PUBLIC_TOP_LEVEL_TYPES =
            setOf(
                "LockdownPreset",
                "LockdownSyscall",
                "Mazewall",
                "MazewallInstallationException",
                "ProcessInstallation",
                "ProcessPolicy",
                "RuntimeProfile",
            )

        val FORBIDDEN_PACKAGE_PREFIXES =
            listOf(
                "io/mazewall/landlock/",
                "io/mazewall/profiler/",
                "io/mazewall/portal/",
                "io/mazewall/enforcer/internal/supervisor/",
                "io/mazewall/platform/seccomp/",
                "io/mazewall/platform/process/",
                "io/mazewall/lockdown/internal/landlock/",
                "io/mazewall/lockdown/internal/profiler/",
                "io/mazewall/lockdown/internal/portal/",
                "io/mazewall/lockdown/internal/enforcer/internal/supervisor/",
                "io/mazewall/lockdown/internal/platform/seccomp/",
                "io/mazewall/lockdown/internal/platform/process/",
                "io/mazewall/lockdown/internal/platform/native/",
            )

        val FORBIDDEN_SIMPLE_NAMES =
            setOf(
                "LiveStackBootstrap.class",
                "ProductionDeploymentVerifier.class",
                "ContainedExecutors.class",
                "Platform.class",
                "FileSystemPolicy.class",
                "WorkerPolicyBuilder.class",
                "SupervisedKind.class",
                "UserNotif.class",
            )

        val ALLOWLISTED_PACKAGE_PREFIXES =
            listOf(
                "io/mazewall/lockdown/",
            )
    }
}

val jarTask = tasks.named<org.gradle.jvm.tasks.Jar>("jar")
val sourcesJarTask = tasks.named<org.gradle.jvm.tasks.Jar>("sourcesJar")
val javadocJarTask = tasks.named<org.gradle.jvm.tasks.Jar>("javadocJar")
val generatePomTask = tasks.named<GenerateMavenPom>("generatePomFileForMavenJavaPublication")

val verifyLockdownPublication =
    tasks.register<VerifyLockdownPublication>("verifyLockdownPublication") {
        group = "verification"
        description = "Verifies that mazewall-lockdown JAR classes, JPMS descriptor, and POM metadata stay minimal and allowlisted."
        dependsOn(jarTask, sourcesJarTask, javadocJarTask, generatePomTask)
        jarFile.set(jarTask.flatMap { it.archiveFile })
        sourcesJarFile.set(sourcesJarTask.flatMap { it.archiveFile })
        javadocJarFile.set(javadocJarTask.flatMap { it.archiveFile })
        pomFile.fileProvider(generatePomTask.map { it.destination })
    }

tasks.named("check") {
    dependsOn(verifyLockdownPublication)
}

tasks.named("unitCheck") {
    dependsOn(verifyLockdownPublication)
}
