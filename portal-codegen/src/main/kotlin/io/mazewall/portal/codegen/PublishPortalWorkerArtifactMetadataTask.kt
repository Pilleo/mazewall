package io.mazewall.portal.codegen

import io.mazewall.portal.PortalWorkerArtifact
import io.mazewall.portal.PortalWorkerArtifactCatalog
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

/** Publishes host-side references to the verified bundle produced by this project. */
public abstract class PublishPortalWorkerArtifactMetadataTask : DefaultTask() {
    @get:Input
    public abstract val registrations: ListProperty<PortalServiceRegistration>

    @get:InputFile
    public abstract val archiveFile: RegularFileProperty

    @get:InputFile
    public abstract val digestFile: RegularFileProperty

    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @TaskAction
    public fun publish() {
        val registrationsByArtifact = registrations.get().groupBy { it.workerArtifact }
        val output = outputDirectory.get().asFile.toPath()
        val root = output.resolve("META-INF/mazewall/portal/worker-artifacts")
        Files.createDirectories(root)
        val digest = Files.readString(digestFile.get().asFile.toPath()).trim()
        val artifact = PortalWorkerArtifact(archiveFile.get().asFile.toPath(), digest)
        registrationsByArtifact.keys.sorted().forEach { key ->
            require(key.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
                "portal worker artifact key must be URL-safe text: $key"
            }
            Files.writeString(root.resolve(key), PortalWorkerArtifactCatalog.encode(artifact))
        }
        Files.writeString(
            output.resolve("META-INF/mazewall/portal/worker-artifacts.index"),
            registrationsByArtifact.keys.sorted().joinToString(separator = "\n", postfix = if (registrationsByArtifact.isEmpty()) "" else "\n"),
        )
    }
}
