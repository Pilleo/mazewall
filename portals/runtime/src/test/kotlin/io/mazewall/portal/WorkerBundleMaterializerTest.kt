package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkerBundleMaterializerTest {
    @Test
    fun `rejects an artifact whose digest does not match`() {
        val artifact = Files.createTempFile("portal-worker", ".jar")
        Files.writeString(artifact, "not a worker bundle")

        assertThrows(PortalWorkerArtifactUnavailableException::class.java) {
            WorkerBundleMaterializer.materialize(
                PortalWorkerArtifact(artifact, "00".repeat(32)),
                Files.createTempDirectory("portal-cache"),
            )
        }
    }

    @Test
    fun `publishes a verified bundle only after extraction completes`() {
        val artifact = zipOf("lib/worker.jar" to byteArrayOf(1, 2, 3))
        val cache = Files.createTempDirectory("portal-cache")

        val materialized = WorkerBundleMaterializer.materialize(PortalWorkerArtifact(artifact, sha256(artifact)), cache)

        assertTrue(Files.isRegularFile(materialized.directory.resolve("lib/worker.jar")))
        assertTrue(Files.readAllBytes(materialized.directory.resolve("lib/worker.jar")).contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(
            materialized.classpath
            .single()
            .fileName
            .toString() == "worker.jar",
        )
    }

    @Test
    fun `rejects bundles with too many entries without publishing them`() {
        val artifact = zipOf(*(0..256).map { "lib/$it.jar" to byteArrayOf(it.toByte()) }.toTypedArray())
        val cache = Files.createTempDirectory("portal-cache")

        assertThrows(IllegalArgumentException::class.java) {
            WorkerBundleMaterializer.materialize(PortalWorkerArtifact(artifact, sha256(artifact)), cache)
        }

        Files.list(cache).use { entries -> assertTrue(entries.noneMatch { !it.fileName.toString().startsWith(".worker-") }) }
    }

    @Test
    fun `rejects a symbolic link presented as the worker artifact`() {
        val target = zipOf("lib/worker.jar" to byteArrayOf(1))
        val linkedArtifact = Files.createTempDirectory("portal-link").resolve("linked-worker.zip")
        Files.createSymbolicLink(linkedArtifact, target)

        assertThrows(PortalWorkerArtifactUnavailableException::class.java) {
            WorkerBundleMaterializer.materialize(
                PortalWorkerArtifact(linkedArtifact, sha256(target)),
                Files.createTempDirectory("portal-cache"),
            )
        }
    }

    @Test
    fun `rejects a symbolic link presented as the cache directory`() {
        val artifact = zipOf("lib/worker.jar" to byteArrayOf(1))
        val realCache = Files.createTempDirectory("portal-real-cache")
        val linkedCache = Files.createTempDirectory("portal-cache-link").resolve("cache")
        Files.createSymbolicLink(linkedCache, realCache)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                WorkerBundleMaterializer.materialize(PortalWorkerArtifact(artifact, sha256(artifact)), linkedCache)
            }

        assertTrue(failure.message!!.contains("symbolic link"), failure.message)
        Files.list(realCache).use { entries -> assertTrue(entries.noneMatch { true }) }
    }

    @Test
    fun `rejects an unpublished cache collision`() {
        val artifact = zipOf("lib/worker.jar" to byteArrayOf(1))
        val digest = sha256(artifact)
        val cache = Files.createTempDirectory("portal-cache")
        Files.createDirectory(cache.resolve(digest))

        assertThrows(IllegalArgumentException::class.java) {
            WorkerBundleMaterializer.materialize(PortalWorkerArtifact(artifact, digest), cache)
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>) =
        Files.createTempFile("portal-worker", ".zip").also { archive ->
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: java.nio.file.Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}
