package io.mazewall.portal.codegen

import io.mazewall.portal.PortalWorkerArtifactCatalog
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

/** Publishes portable host metadata and opaque bundle bytes, never guest classes. */
@CacheableTask
public abstract class PublishPortalWorkerArtifactMetadataTask : DefaultTask() {
    @get:Input
    public abstract val registrations: ListProperty<PortalServiceRegistration>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val archiveFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val digestFile: RegularFileProperty

    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @TaskAction
    @Suppress("TooGenericExceptionCaught") // Failed publication must retire its exclusively owned staging tree and preserve fatal precedence.
    public fun publish() {
        val staging = Files.createTempDirectory(temporaryDir.toPath(), "portal-worker-metadata-")
        try {
            writeMetadata(staging)
            GeneratedOutputPublisher.publish(listOf(staging to outputDirectory.get().asFile.toPath()))
        } catch (failure: Throwable) {
            val cleanup = runCatching { check(!Files.exists(staging) || staging.toFile().deleteRecursively()) { "failed to retire worker metadata staging" } }.exceptionOrNull()
            throw (
                cleanup?.let {
                io.mazewall.platform.fd.CleanupFailures
                .merge(failure, it)
            } ?: failure
            )
        }
    }

    private fun writeMetadata(output: java.nio.file.Path) {
        val registrationsByArtifact = registrations.get().groupBy { it.workerArtifact }
        if (registrationsByArtifact.isEmpty()) return
        val root = output.resolve("META-INF/mazewall/portal/worker-artifacts")
        Files.createDirectories(root)
        val digest = Files.readString(digestFile.get().asFile.toPath()).trim()
        val resourceName = "META-INF/mazewall/portal/worker-bundles/${digest.lowercase()}.zip"
        val record = PortalWorkerArtifactCatalog.encodeResource(resourceName, digest)
        val resource = output.resolve(resourceName)
        Files.createDirectories(resource.parent)
        Files.copy(archiveFile.get().asFile.toPath(), resource)
        registrationsByArtifact.keys.sorted().forEach { key ->
            require(key.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
                "portal worker artifact key must be URL-safe text: $key"
            }
            Files.writeString(root.resolve(key), record)
        }
        Files.writeString(
            output.resolve("META-INF/mazewall/portal/worker-artifacts.index"),
            registrationsByArtifact.keys.sorted().joinToString(separator = "\n", postfix = if (registrationsByArtifact.isEmpty()) "" else "\n"),
        )
    }
}
