package io.mazewall.portal.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packages the curated worker runtime as a deterministic ZIP consumed by the portal runtime. */
@CacheableTask
public abstract class PortalWorkerBundleTask : DefaultTask() {
    @get:Classpath
    public abstract val workerJars: ConfigurableFileCollection

    @get:OutputFile
    public abstract val archiveFile: RegularFileProperty

    /** SHA-256 of [archiveFile], suitable for a [io.mazewall.portal.PortalWorkerArtifact]. */
    @get:OutputFile
    public abstract val digestFile: RegularFileProperty

    @TaskAction
    public fun bundle() {
        val jars =
            workerJars.files
                .asSequence()
                .filter { it.isFile && it.extension == "jar" }
                .map { file -> WorkerJar(file, sha256(file.toPath())) }
                .sortedWith(compareBy<WorkerJar> { it.digest }.thenBy { it.file.name })
                .distinctBy { it.digest }
                .toList()
        require(jars.isNotEmpty()) { "portal worker bundle has no JAR inputs" }
        val output = archiveFile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            jars.forEach { jar ->
                val entry = ZipEntry("lib/${jar.digest.take(16)}-${jar.file.name}")
                entry.time = 0L
                zip.putNextEntry(entry)
                Files.newInputStream(jar.file.toPath()).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val digestOutput = digestFile.get().asFile.toPath()
        Files.createDirectories(digestOutput.parent)
        Files.writeString(digestOutput, "${sha256(output)}\n")
    }

    private data class WorkerJar(
        val file: java.io.File,
        val digest: String,
    )

    private fun sha256(file: java.nio.file.Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(Locale.ROOT, it) }
}
