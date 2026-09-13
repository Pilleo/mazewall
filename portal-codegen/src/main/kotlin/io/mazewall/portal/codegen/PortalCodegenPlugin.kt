package io.mazewall.portal.codegen

import io.mazewall.portal.PortalGeneratedNames
import io.mazewall.portal.worker.PortalWorkerMain
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
import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

public interface PortalCodegenExtension {
    public val interfaces: ListProperty<String>

    /** Explicit service-to-worker bindings emitted as generated manifest resources. */
    public val registrations: ListProperty<PortalServiceRegistration>

    /** Explicit guest source set. Unset means host stubs only; main/test are never worker outputs. */
    public val workerSourceSet: Property<String>
}

@CacheableTask
public abstract class GeneratePortalStubsTask : DefaultTask() {
    @get:Input
    public abstract val generateWorker: Property<Boolean>

    @get:Input
    public abstract val interfaceNames: ListProperty<String>

    @get:Input
    public abstract val registrations: ListProperty<PortalServiceRegistration>

    @get:Classpath
    public abstract val classpath: ConfigurableFileCollection

    @get:OutputDirectory
    public abstract val stubOutputDir: DirectoryProperty

    @get:OutputDirectory
    public abstract val dispatcherOutputDir: DirectoryProperty

    @get:OutputDirectory
    public abstract val manifestOutputDir: DirectoryProperty

    @get:OutputDirectory
    public abstract val workerResourceDir: DirectoryProperty

    @TaskAction
    public fun generate() {
        val stagingRoot = Files.createTempDirectory(temporaryDir.toPath(), "portal-codegen-")
        val stagedStubs = Files.createDirectory(stagingRoot.resolve("stubs"))
        val stagedDispatchers = Files.createDirectory(stagingRoot.resolve("dispatchers"))
        val stagedManifests = Files.createDirectory(stagingRoot.resolve("manifests"))
        val stagedResources = Files.createDirectory(stagingRoot.resolve("resources"))
        try {
            val urls = classpath.files.map { it.toURI().toURL() }.toTypedArray()
            URLClassLoader(urls, PortalStubGenerator::class.java.classLoader).use { loader ->
                val configuredRegistrations = registrations.get()
                require(configuredRegistrations.map { it.serviceId }.distinct().size == configuredRegistrations.size) {
                    "portal service registrations must have unique serviceId values"
                }
                require(configuredRegistrations.isEmpty() || generateWorker.get()) {
                    "portal service registrations require a dedicated workerSourceSet"
                }
                val services =
                    (interfaceNames.get() + configuredRegistrations.map { it.contractName })
                    .distinct()
                    .sorted()
                    .map { name -> Class.forName(name, false, loader) }
                PortalGeneratedNames.requireUnique(services)
                services.forEach { type ->
                    writeService(type, stagedStubs, stagedDispatchers, stagedResources)
                }
                val servicesByName = services.associateBy { it.name }
                configuredRegistrations.sortedBy { it.serviceId }.forEach { registration ->
                    val manifest = PortalManifestGenerator.generate(servicesByName.getValue(registration.contractName), registration)
                    writeManifest(manifest, stagedManifests)
                    writeManifest(manifest, stagedResources)
                }
                if (configuredRegistrations.isNotEmpty()) {
                    writeManifestIndex(configuredRegistrations, stagedManifests)
                    writeManifestIndex(configuredRegistrations, stagedResources)
                }
            }
            GeneratedOutputPublisher.publish(
                listOf(
                    stagedStubs to stubOutputDir.get().asFile.toPath(),
                    stagedDispatchers to dispatcherOutputDir.get().asFile.toPath(),
                    stagedManifests to manifestOutputDir.get().asFile.toPath(),
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
            val dispatcherName = PortalGeneratedNames.dispatcherBinaryName(type)
            Files.writeString(descriptor, "$dispatcherName\n")
        }
    }

    private fun writeManifest(
        manifest: io.mazewall.portal.PortalServiceManifest,
        resources: Path,
    ) {
        val file = resources.resolve("META-INF/mazewall/portal/services/${manifest.serviceId.value}")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            io.mazewall.portal.PortalServiceManifestCodec
            .encode(manifest),
        )
    }

    private fun writeManifestIndex(
        registrations: List<PortalServiceRegistration>,
        resources: Path,
    ) {
        val index = resources.resolve("META-INF/mazewall/portal/services.index")
        Files.createDirectories(index.parent)
        Files.writeString(index, registrations.map { it.serviceId }.sorted().joinToString(separator = "\n", postfix = "\n"))
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
        extension.registrations.convention(emptyList())
        project.pluginManager.withPlugin("java") {
            if (!project.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
                project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            }
        }
        val generate =
            project.tasks.register("generatePortalStubs", GeneratePortalStubsTask::class.java) { task ->
                task.group = "codegen"
                task.description = "Generate process-portal host stubs and worker dispatchers"
                task.interfaceNames.set(extension.interfaces)
                task.registrations.set(extension.registrations)
                task.generateWorker.set(extension.workerSourceSet.map { it.isNotBlank() }.orElse(false))
                task.stubOutputDir.set(project.layout.buildDirectory.dir("generated/portal-stubs"))
                task.dispatcherOutputDir.set(project.layout.buildDirectory.dir("generated/portal-dispatchers"))
                task.manifestOutputDir.set(project.layout.buildDirectory.dir("generated/portal-manifests"))
                task.workerResourceDir.set(project.layout.buildDirectory.dir("generated/portal-worker-resources"))
                project.configurations.findByName("compileClasspath")?.let { task.classpath.from(it) }
            }
        project.tasks.register("portalCheck", PortalCheckTask::class.java) { task ->
            task.group = "verification"
            task.description = "Validate configured portal contracts and generated worker metadata"
            task.dependsOn(generate)
        }
        project.tasks.register("portalDescribe", PortalDescribeTask::class.java) { task ->
            task.group = "help"
            task.description = "Describe configured portal service bindings without executing guest code"
            task.registrations.set(extension.registrations)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            configureKotlinPlugin(project, extension, generate)
        }
    }

    private fun configureKotlinPlugin(
        project: Project,
        extension: PortalCodegenExtension,
        generate: org.gradle.api.tasks.TaskProvider<GeneratePortalStubsTask>,
    ) {
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
            val java = project.extensions.getByType(JavaPluginExtension::class.java)
            java.sourceSets
                .getByName("main")
                .resources
                .srcDir(generate.map { task -> task.manifestOutputDir })
            project.afterEvaluate {
                val workerName = extension.workerSourceSet.orNull ?: return@afterEvaluate
                require(workerName.matches(Regex("[a-zA-Z][a-zA-Z0-9]*")) && workerName !in setOf("main", "test")) {
                    "portalCodegen.workerSourceSet must name a dedicated worker source set (not main or test)"
                }
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
                val workerBundle = project.tasks.register("portalWorkerBundle", PortalWorkerBundleTask::class.java) { task ->
                    task.group = "build"
                    task.description = "Package the curated portal worker runtime bundle"
                    task.workerJars.from(worker.runtimeClasspath.filter { file -> file.isFile && file.extension == "jar" })
                    // This is the plugin's declared portal-worker runtime, not the host application's classpath.
                    task.workerJars.from(
                        File(
                            PortalWorkerMain::class.java.protectionDomain.codeSource.location
                            .toURI(),
                        ),
                    )
                    task.workerJars.from(workerJar.flatMap { it.archiveFile })
                    task.archiveFile.set(project.layout.buildDirectory.file("libs/${project.name}-portal-worker.zip"))
                    task.digestFile.set(project.layout.buildDirectory.file("libs/${project.name}-portal-worker.zip.sha256"))
                    task.dependsOn(workerJar)
                }
                val publishWorkerArtifactMetadata =
                    project.tasks.register("publishPortalWorkerArtifactMetadata", PublishPortalWorkerArtifactMetadataTask::class.java) { task ->
                        task.group = "build"
                        task.description = "Publish host metadata for the verified portal worker bundle"
                        task.registrations.set(extension.registrations)
                        task.archiveFile.set(workerBundle.flatMap { it.archiveFile })
                        task.digestFile.set(workerBundle.flatMap { it.digestFile })
                        task.outputDirectory.set(project.layout.buildDirectory.dir("generated/portal-worker-artifacts"))
                        task.dependsOn(workerBundle)
                    }
                java.sourceSets
                    .getByName("main")
                    .resources
                    .srcDir(publishWorkerArtifactMetadata.map { metadataTask -> metadataTask.outputDirectory })
                project.tasks.named("portalCheck").configure { task -> task.dependsOn(workerBundle) }
                project.tasks.named("assemble").configure { task -> task.dependsOn(publishWorkerArtifactMetadata) }
            }
    }
}

private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
