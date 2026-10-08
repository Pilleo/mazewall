import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(25)
}

val portalCodegenKotlin = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
val portalCodegenPortalJar = project(":portals:runtime").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val portalCodegenPlatformJar = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
portalCodegenKotlin.target.compilations.matching { it.name == "test" }.configureEach {
    compileTaskProvider.configure {
        dependsOn(portalCodegenPortalJar, portalCodegenPlatformJar)
        compilerOptions.freeCompilerArgs.addAll(
            portalCodegenPortalJar.zip(portalCodegenPlatformJar) { portal, platform ->
                listOf(
                    "-Xfriend-paths=${portal.asFile.absolutePath}",
                    "-Xfriend-paths=${platform.asFile.absolutePath}",
                )
            },
        )
    }
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    compileOnly(kotlin("reflect"))
    implementation(libs.kotlinpoet)
    implementation(project(":portals:runtime"))
    implementation(project(":portals:worker"))
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation(kotlin("reflect"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

gradlePlugin {
    plugins {
        create("portalCodegen") {
            id = "io.mazewall.portal.codegen"
            implementationClass = "io.mazewall.portal.codegen.PortalCodegenPlugin"
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.javaParameters.set(true)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":portals:runtime:jar", ":platform:jar", ":enforcer:jar")
    systemProperty(
        "portal.codegen.portalJar",
        project(":portals:runtime")
            .tasks
            .named<Jar>("jar")
            .flatMap { it.archiveFile }
            .get()
            .asFile,
    )
    systemProperty(
        "portal.codegen.platformJar",
        project(":platform")
            .tasks
            .named<Jar>("jar")
            .flatMap { it.archiveFile }
            .get()
            .asFile,
    )
    systemProperty(
        "portal.codegen.enforcerJar",
        project(":enforcer")
            .tasks
            .named<Jar>("jar")
            .flatMap { it.archiveFile }
            .get()
            .asFile,
    )
}

/** Receipt for the actual Gradle plugin and its resolved producer closure, never generated-source copies. */
abstract class ExportPortalPluginClasspath : DefaultTask() {
    @get:Classpath abstract val pluginClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val producerSources: ConfigurableFileCollection

    @get:Input abstract val producerRoot: Property<String>

    @get:Input abstract val producerVersion: Property<String>

    @get:Input abstract val producerRevision: Property<String>

    @get:OutputFile abstract val receipt: RegularFileProperty

    @TaskAction
    fun export() {
        fun digest(file: File): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        val root = File(producerRoot.get())
        val properties = Properties()
        properties.setProperty("format", "1")
        properties.setProperty("root", root.canonicalPath)
        properties.setProperty("version", producerVersion.get())
        properties.setProperty("revision", producerRevision.get())
        producerSources.files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach {
            properties.setProperty("source.${it.relativeTo(root).invariantSeparatorsPath}", digest(it))
        }
        val jars = pluginClasspath.files.toList()
        require(jars.isNotEmpty() && jars.all { it.isFile && it.extension == "jar" }) {
            "Portal plugin classpath must contain only existing producer/runtime JARs"
        }
        properties.setProperty("classpath", jars.joinToString(File.pathSeparator) { it.canonicalPath })
        jars.forEach { properties.setProperty("artifact.${it.canonicalPath}", digest(it)) }
        val target = receipt.get().asFile
        target.parentFile.mkdirs()
        // A failed write must not expose a partially written usable receipt.
        val temporary = File.createTempFile("portal-codegen-", ".properties", target.parentFile)
        try {
            temporary.outputStream().use { properties.store(it, "Actual Portal plugin producer receipt") }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
        logger.lifecycle("Portal plugin producer version={} revision={} receipt={}", producerVersion.get(), producerRevision.get(), target)
    }
}

tasks.register<ExportPortalPluginClasspath>("exportPluginClasspath") {
    group = "build"
    description = "Build and export the actual Portal plugin classpath with source/content freshness checks"
    pluginClasspath.from(tasks.named<Jar>("jar").flatMap { it.archiveFile }, configurations.runtimeClasspath)
    // Keep this producer inventory in sync with the demo's pre-buildscript validation.
    producerSources.from(
        rootProject.fileTree(rootProject.projectDir) {
            include("*.gradle", "*.gradle.kts", "gradle.properties", "gradlew", "gradle/**")
            listOf("buildSrc", "platform", "enforcer", "profiler", "portals/runtime", "portals/worker", "portals/codegen").forEach {
                include("$it/src/**", "$it/*.gradle", "$it/*.gradle.kts")
            }
        },
    )
    producerRoot.set(rootProject.projectDir.canonicalPath)
    producerVersion.set(provider { project.version.toString() })
    producerRevision.set(providers.gradleProperty("portalCodegenRevision").orElse("working-tree"))
    receipt.set(rootProject.layout.buildDirectory.file("portal-codegen/plugin-classpath.properties"))
    // Always reissue after the producer tasks; source inventory also covers additions/deletions.
    outputs.upToDateWhen { false }
}
