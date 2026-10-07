package io.mazewall.portal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorkerArtifactResourceMaterializerTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `resource bytes relocate into a verified cache rather than an original build path`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = directory.resolve("opaque.zip")
        Files.write(source, bytes)
        val artifact = WorkerArtifactResourceMaterializer.materialize(source.toUri().toURL(), sha(bytes), directory.resolve("cache"))
        Files.delete(source)
        assertContentEquals(bytes, Files.readAllBytes(artifact.path))
    }

    @Test
    fun `oversized archive rejects during bounded copy and removes staging`() {
        val source = directory.resolve("large.zip")
        java.nio.channels.FileChannel.open(source, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { file ->
            file.position(64L * 1024L * 1024L)
            file.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
        }
        val cache = directory.resolve("cache")
        val failure = assertFailsWith<IllegalArgumentException> { WorkerArtifactResourceMaterializer.materialize(source.toUri().toURL(), "00".repeat(32), cache) }
        kotlin.test.assertTrue(failure.message.orEmpty().contains("exceeds"))
        Files.list(cache).use { assertFalse(it.findAny().isPresent) }
    }

    @Test
    fun `wrong digest never publishes resource bytes`() {
        val source = directory.resolve("opaque.zip")
        Files.write(source, byteArrayOf(1, 2, 3))
        val cache = directory.resolve("cache")
        assertFailsWith<IllegalArgumentException> { WorkerArtifactResourceMaterializer.materialize(source.toUri().toURL(), "00".repeat(32), cache) }
        Files.list(cache).use { assertFalse(it.findAny().isPresent) }
    }

    @Test
    fun `symlinked cache destination cannot supply or redirect verified resources`() {
        val bytes = byteArrayOf(1, 2, 3)
        val source = directory.resolve("opaque.zip")
        Files.write(source, bytes)
        val real = directory.resolve("real")
        Files.createDirectories(real)
        val alias = directory.resolve("alias")
        Files.createSymbolicLink(alias, real)
        assertFailsWith<IllegalArgumentException> { WorkerArtifactResourceMaterializer.materialize(source.toUri().toURL(), sha(bytes), alias.resolve("nested")) }
        assertFailsWith<IllegalArgumentException> { WorkerBundleMaterializer.materialize(PortalWorkerArtifact(source, sha(bytes)), alias.resolve("nested")) }
        val cached = WorkerArtifactResourceMaterializer.materialize(source.toUri().toURL(), sha(bytes), real)
        Files.delete(cached.path)
        Files.createSymbolicLink(cached.path, source)
        assertFailsWith<IllegalArgumentException> { WorkerArtifactResourceMaterializer.materialize(source.toUri().toURL(), sha(bytes), real) }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
