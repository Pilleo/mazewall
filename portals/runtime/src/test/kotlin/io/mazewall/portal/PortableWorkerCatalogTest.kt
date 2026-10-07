package io.mazewall.portal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortableWorkerCatalogTest {
    @TempDir lateinit var directory: Path
    private val digest = "ab".repeat(32)
    private val resource = "META-INF/mazewall/portal/worker-bundles/$digest.zip"

    @Test
    fun `missing opaque resource never falls back to a local build path`() {
        val root = directory.resolve("host")
        metadata(root, PortalWorkerArtifactCatalog.encodeResource(resource, digest))
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            val failure = assertFailsWith<IllegalArgumentException> { PortalWorkerArtifactCatalog.load(loader, directory.resolve("cache")) }
            assertTrue(failure.message.orEmpty().contains("found 0"))
        }
    }

    @Test
    fun `duplicate opaque resources reject before either can become authority`() {
        val first = directory.resolve("first")
        val second = directory.resolve("second")
        metadata(first, PortalWorkerArtifactCatalog.encodeResource(resource, digest))
        for (root in listOf(first, second)) {
            Files.createDirectories(root.resolve(resource).parent)
            Files.write(root.resolve(resource), byteArrayOf(1))
        }
        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            val failure = assertFailsWith<IllegalArgumentException> { PortalWorkerArtifactCatalog.load(loader, directory.resolve("cache")) }
            assertTrue(failure.message.orEmpty().contains("found 2"))
        }
    }

    @Test
    fun `unknown versions fields and unsafe names fail at catalog loading`() {
        val records = listOf(
            "format=1\npath=/old/build/worker.zip\nsha256=$digest\n",
            "format=3\nresource=$resource\nsha256=$digest\n",
            "format=2\nresource=$resource\nsha256=$digest\napproved=true\n",
            "format=2\nresource=../worker.zip\nsha256=$digest\n",
        )
        records.forEachIndexed { index, record ->
            val root = directory.resolve("host-$index")
            metadata(root, record)
            URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
                assertFailsWith<IllegalArgumentException> { PortalWorkerArtifactCatalog.load(loader, directory.resolve("cache-$index")) }
            }
        }
    }

    private fun metadata(
        root: Path,
        record: String,
    ) {
        val artifacts = root.resolve("META-INF/mazewall/portal/worker-artifacts")
        Files.createDirectories(artifacts)
        Files.writeString(artifacts.resolve("worker"), record)
        Files.writeString(artifacts.resolveSibling("worker-artifacts.index"), "worker\n")
    }
}
