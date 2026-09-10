package io.mazewall.portal.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

public abstract class PortalCodegenExtension {
    public abstract val interfaces: ListProperty<String>
}

@CacheableTask
public abstract class GeneratePortalStubsTask : DefaultTask() {
    @get:Input
    public abstract val interfaceNames: ListProperty<String>

    @get:Classpath
    public abstract val classpath: ConfigurableFileCollection

    @get:OutputDirectory
    public abstract val stubOutputDir: DirectoryProperty

    @get:OutputDirectory
    public abstract val dispatcherOutputDir: DirectoryProperty

    @TaskAction
    public fun generate() {
        val stagingRoot = Files.createTempDirectory(temporaryDir.toPath(), "portal-codegen-")
        val stagedStubs = Files.createDirectory(stagingRoot.resolve("stubs"))
        val stagedDispatchers = Files.createDirectory(stagingRoot.resolve("dispatchers"))
        try {
            val urls = classpath.files.map { it.toURI().toURL() }.toTypedArray()
            URLClassLoader(urls, PortalStubGenerator::class.java.classLoader).use { loader ->
                interfaceNames.get().distinct().sorted().forEach { name ->
                    val type = Class.forName(name, false, loader)
                    PortalStubGenerator.write(type, stagedStubs.toFile(), stagedDispatchers.toFile())
                }
            }
            GeneratedOutputPublisher.publish(
                listOf(
                    stagedStubs to stubOutputDir.get().asFile.toPath(),
                    stagedDispatchers to dispatcherOutputDir.get().asFile.toPath(),
                ),
            )
        } finally {
            deleteTree(stagingRoot)
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
                task.stubOutputDir.set(project.layout.buildDirectory.dir("generated/portal-stubs"))
                task.dispatcherOutputDir.set(project.layout.buildDirectory.dir("generated/portal-dispatchers"))
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
        }
    }
}

private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
