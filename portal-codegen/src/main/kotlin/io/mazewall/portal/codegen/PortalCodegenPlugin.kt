package io.mazewall.portal.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

public abstract class PortalCodegenExtension {
    public abstract val interfaces: ListProperty<String>

    /** Explicit guest source set. Unset means host stubs only; main/test are never worker outputs. */
    public abstract val workerSourceSet: Property<String>
}

@CacheableTask
public abstract class GeneratePortalStubsTask : DefaultTask() {
    @get:Input
    public abstract val generateWorker: Property<Boolean>

    @get:Input
    public abstract val interfaceNames: ListProperty<String>

    @get:Classpath
    public abstract val classpath: ConfigurableFileCollection

    @get:OutputDirectory
    public abstract val stubOutputDir: DirectoryProperty

    @get:OutputDirectory
    public abstract val dispatcherOutputDir: DirectoryProperty

    @get:OutputDirectory
    public abstract val workerResourceDir: DirectoryProperty

    @TaskAction
    public fun generate() {
        val stagingRoot = Files.createTempDirectory(temporaryDir.toPath(), "portal-codegen-")
        val stagedStubs = Files.createDirectory(stagingRoot.resolve("stubs"))
        val stagedDispatchers = Files.createDirectory(stagingRoot.resolve("dispatchers"))
        val stagedResources = Files.createDirectory(stagingRoot.resolve("resources"))
        try {
            val urls = classpath.files.map { it.toURI().toURL() }.toTypedArray()
            URLClassLoader(urls, PortalStubGenerator::class.java.classLoader).use { loader ->
                interfaceNames.get().distinct().sorted().forEach { name ->
                    val type = Class.forName(name, false, loader)
                    writeService(type, stagedStubs, stagedDispatchers, stagedResources)
                }
            }
            GeneratedOutputPublisher.publish(
                listOf(
                    stagedStubs to stubOutputDir.get().asFile.toPath(),
                    stagedDispatchers to dispatcherOutputDir.get().asFile.toPath(),
                    stagedResources to workerResourceDir.get().asFile.toPath(),
                ),
            )
        } finally {
            deleteTree(stagingRoot)
        }
    }

    private fun writeService(
        type: Class<*>,
        stubs: Path,
        dispatchers: Path,
        resources: Path,
    ) {
        PortalStubGenerator.generate(type).forEach { spec ->
            val dispatcher = spec.name.endsWith("PortalDispatcher")
            if (!dispatcher || generateWorker.get()) spec.writeTo(if (dispatcher) dispatchers else stubs)
        }
        if (generateWorker.get()) {
            val descriptor = resources.resolve("META-INF/mazewall/portal/${type.name}")
            Files.createDirectories(descriptor.parent)
            val dispatcherName = listOf(type.packageName, type.simpleName + "PortalDispatcher").filter(String::isNotEmpty).joinToString(".")
            Files.writeString(descriptor, "$dispatcherName\n")
        }
    }
}

internal object GeneratedOutputPublisher {
    fun publish(outputs: List<Pair<Path, Path>>) {
        require(outputs.map { it.second.toAbsolutePath().normalize() }.distinct().size == outputs.size) {
            "portal generated output directories must be distinct"
        }
        val entries =
            outputs.map { (staged, destination) ->
                Files.createDirectories(destination.toAbsolutePath().normalize().parent)
                PublishedOutput(
                    staged.toAbsolutePath().normalize(),
                    destination.toAbsolutePath().normalize(),
                    destination.resolveSibling(".${destination.fileName}.backup-${UUID.randomUUID()}"),
                )
            }
        val failure =
            runCatching {
                entries.forEach { entry ->
                    if (Files.exists(entry.destination)) {
                        atomicMove(entry.destination, entry.backup)
                        entry.backedUp = true
                    }
                    atomicMove(entry.staged, entry.destination)
                    entry.published = true
                }
            }.exceptionOrNull()
        if (failure != null) rollback(entries, failure)
        entries.forEach { entry -> if (entry.backedUp) deleteTree(entry.backup) }
    }

    private fun atomicMove(
        source: Path,
        destination: Path,
    ) {
        try {
            Files.move(source, destination, ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IOException("portal generated outputs require atomic directory moves: $source -> $destination", unsupported)
        }
    }

    private fun rollback(
        entries: List<PublishedOutput>,
        original: Throwable,
    ): Nothing {
        var failure = original
        entries.asReversed().forEach { entry ->
            val cleanup = cleanupFailure(entry)
            if (cleanup != null) failure = preferFatal(failure, cleanup)
        }
        throw failure
    }

    private fun cleanupFailure(entry: PublishedOutput): Throwable? =
        runCatching {
            if (entry.published) deleteTree(entry.destination)
            if (entry.backedUp && Files.exists(entry.backup)) atomicMove(entry.backup, entry.destination)
        }.exceptionOrNull()

    private fun preferFatal(
        current: Throwable,
        cleanup: Throwable,
    ): Throwable =
        if (cleanup is Error && current !is Error) {
            cleanup.addSuppressed(current)
            cleanup
        } else {
            current.addSuppressed(cleanup)
            current
        }

    private data class PublishedOutput(
        val staged: Path,
        val destination: Path,
        val backup: Path,
        var backedUp: Boolean = false,
        var published: Boolean = false,
    )
}

public class PortalCodegenPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("portalCodegen", PortalCodegenExtension::class.java)
        extension.interfaces.convention(emptyList())
        val generate =
            project.tasks.register("generatePortalStubs", GeneratePortalStubsTask::class.java) { task ->
                task.group = "codegen"
                task.description = "Generate process-portal host stubs and worker dispatchers"
                task.interfaceNames.set(extension.interfaces)
                task.generateWorker.set(extension.workerSourceSet.map { it.isNotBlank() }.orElse(false))
                task.stubOutputDir.set(project.layout.buildDirectory.dir("generated/portal-stubs"))
                task.dispatcherOutputDir.set(project.layout.buildDirectory.dir("generated/portal-dispatchers"))
                task.workerResourceDir.set(project.layout.buildDirectory.dir("generated/portal-worker-resources"))
                project.configurations.findByName("compileClasspath")?.let { task.classpath.from(it) }
            }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val kotlin = project.extensions.getByName("kotlin")

            val sourceSets =
                kotlin.javaClass.methods
                    .single { method -> method.name == "getSourceSets" && method.parameterCount == 0 }
                    .invoke(kotlin) as NamedDomainObjectContainer<*>
            val main = sourceSets.getByName("main")
            val kotlinSources =
                main.javaClass.methods
                    .single { method -> method.name == "getKotlin" && method.parameterCount == 0 }
                    .invoke(main) as SourceDirectorySet
            kotlinSources.srcDir(generate.map { task -> task.stubOutputDir })
            project.tasks.named("compileKotlin").configure { task -> task.dependsOn(generate) }
            project.afterEvaluate {
                val workerName = extension.workerSourceSet.orNull ?: return@afterEvaluate
                require(workerName.matches(Regex("[a-zA-Z][a-zA-Z0-9]*")) && workerName !in setOf("main", "test")) {
                    "portalCodegen.workerSourceSet must name a dedicated worker source set (not main or test)"
                }
                val java = project.extensions.getByType(JavaPluginExtension::class.java)
                val worker = java.sourceSets.maybeCreate(workerName)
                project.configurations
                    .getByName(worker.implementationConfigurationName)
                    .extendsFrom(project.configurations.getByName("implementation"))
                project.configurations
                    .getByName(worker.runtimeOnlyConfigurationName)
                    .extendsFrom(project.configurations.getByName("runtimeOnly"))
                val kotlinWorker = sourceSets.getByName(workerName)
                val workerSources = kotlinWorker.javaClass.methods
                    .single { method -> method.name == "getKotlin" && method.parameterCount == 0 }
                    .invoke(kotlinWorker) as SourceDirectorySet
                workerSources.srcDir(generate.map { task -> task.dispatcherOutputDir })
                worker.resources.srcDir(generate.map { task -> task.workerResourceDir })
                val workerJar = project.tasks.register("portalWorkerJar", Jar::class.java) { task ->
                    task.group = "build"
                    task.description = "Package guest implementations, generated dispatchers and worker descriptors"
                    task.archiveClassifier.set("portal-worker")
                    task.from(worker.output)
                }
                project.tasks.named("assemble").configure { task -> task.dependsOn(workerJar) }
            }
        }
    }
}

private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
