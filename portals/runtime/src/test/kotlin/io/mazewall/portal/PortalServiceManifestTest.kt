package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PortalServiceManifestTest {
    @TempDir
    lateinit var temporaryDir: Path

    @Test
    fun `manifest retains worker names as metadata`() {
        val manifest =
            PortalServiceManifest(
                serviceId = PortalServiceId("pdf-parser"),
                contractName = "example.DocumentParser",
                implementationName = "example.PdfDocumentParser",
                dispatcherName = "example.DocumentParserPortalDispatcher",
                schemaId = "sha256:abc123",
                isolationGroup = "document-processing",
                instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
                policyProfile = "restricted-parser",
                workerArtifact = "document-parser.portal-worker.jar",
            )

        assertEquals("example.PdfDocumentParser", manifest.implementationName)
        assertEquals("document-processing", manifest.isolationGroup)
    }

    @Test
    fun `manifest rejects unsafe or incomplete bindings`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortalServiceManifest(
                serviceId = PortalServiceId("pdf-parser"),
                contractName = "example.DocumentParser",
                implementationName = "example.DocumentParser",
                dispatcherName = "example.DocumentParserPortalDispatcher",
                schemaId = "sha256:abc123",
                isolationGroup = "document-processing",
                instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
                policyProfile = "restricted-parser",
                workerArtifact = "document-parser.portal-worker.jar",
            )
        }
        assertThrows(IllegalArgumentException::class.java) { PortalServiceId(" ") }
    }

    @Test
    fun `manifest resource round trips deterministically`() {
        val manifest = sampleManifest()

        val encoded = PortalServiceManifestCodec.encode(manifest)

        assertEquals(manifest, PortalServiceManifestCodec.decode(encoded))
        assertTrue(encoded.startsWith("format=1\n"), encoded)
    }

    @Test
    fun `manifest resource rejects duplicate or unknown keys`() {
        val encoded = PortalServiceManifestCodec.encode(sampleManifest())

        val duplicate = encoded + "serviceId=another-service\n"
        val unknown = encoded + "unexpected=value\n"

        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                PortalServiceManifestCodec.decode(duplicate)
            }.message!!.contains("duplicate"),
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                PortalServiceManifestCodec.decode(unknown)
            }.message!!.contains("unknown"),
        )
    }

    @Test
    fun `catalog loads only manifests listed by its index`() {
        val resources = temporaryDir.resolve("META-INF/mazewall/portal/services").createDirectories()
        resources.resolve("pdf-parser").writeText(PortalServiceManifestCodec.encode(sampleManifest()))
        resources.resolveSibling("services.index").writeText("pdf-parser\n")

        URLClassLoader(arrayOf(temporaryDir.toUri().toURL()), null).use { loader ->
            val manifests = PortalServiceManifestCatalog.load(loader)

            assertEquals(listOf(sampleManifest()), manifests)
        }
    }

    @Test
    fun `catalog rejects an index that names a different manifest`() {
        val resources = temporaryDir.resolve("META-INF/mazewall/portal/services").createDirectories()
        resources.resolve("fixture-a").writeText(PortalServiceManifestCodec.encode(sampleManifest()))
        resources.resolveSibling("services.index").writeText("another-service\n")

        URLClassLoader(arrayOf(temporaryDir.toUri().toURL()), null).use { loader ->
            val failure = assertThrows(IllegalArgumentException::class.java) { PortalServiceManifestCatalog.load(loader) }
            assertTrue(failure.message!!.contains("another-service"), failure.message)
        }
    }

    @Test
    fun `worker artifact catalog supplies runtime configuration`() {
        val content = "opaque worker resource"
        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val name = "META-INF/mazewall/portal/worker-bundles/$digest.zip"
        val artifact = temporaryDir.resolve(name)
        artifact.parent.createDirectories()
        artifact.writeText(content)
        val artifactResources = temporaryDir.resolve("META-INF/mazewall/portal/worker-artifacts").createDirectories()
        artifactResources.resolve("fixture-worker").writeText(
            PortalWorkerArtifactCatalog.encodeResource(name, digest),
        )
        artifactResources.resolveSibling("worker-artifacts.index").writeText("fixture-worker\n")

        URLClassLoader(arrayOf(temporaryDir.toUri().toURL()), null).use { loader ->
            val loaded = PortalWorkerArtifactCatalog.load(loader, temporaryDir.resolve("cache")).getValue("fixture-worker")
            assertEquals(digest, loaded.sha256)
            assertEquals(
                content,
                java.nio.file.Files
                .readString(loaded.path),
            )
        }
    }

    @Test
    fun `runtime configuration discovers generated manifest and worker artifact together`() {
        val content = "opaque worker resource"
        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val name = "META-INF/mazewall/portal/worker-bundles/$digest.zip"
        val artifact = temporaryDir.resolve(name)
        artifact.parent.createDirectories()
        artifact.writeText(content)
        val serviceResources = temporaryDir.resolve("META-INF/mazewall/portal/services").createDirectories()
        serviceResources.resolve("pdf-parser").writeText(PortalServiceManifestCodec.encode(sampleManifest()))
        serviceResources.resolveSibling("services.index").writeText("pdf-parser\n")
        val artifactResources = temporaryDir.resolve("META-INF/mazewall/portal/worker-artifacts").createDirectories()
        artifactResources.resolve("document-parser.portal-worker.jar").writeText(
            PortalWorkerArtifactCatalog.encodeResource(name, digest),
        )
        artifactResources.resolveSibling("worker-artifacts.index").writeText("document-parser.portal-worker.jar\n")

        URLClassLoader(arrayOf(temporaryDir.toUri().toURL()), null).use { loader ->
            val config = PortalRuntimeConfig.load(loader, workerBundleCache = temporaryDir.resolve("cache"))

            assertEquals(listOf(sampleManifest()), config.manifests)
            val loaded = config.workerArtifacts.getValue("document-parser.portal-worker.jar")
            assertEquals(digest, loaded.sha256)
            assertEquals(
                content,
                java.nio.file.Files
                .readString(loaded.path),
            )
        }
    }

    @Test
    fun `worker artifact catalog rejects resource traversal`() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PortalWorkerArtifactCatalog.encodeResource("META-INF/mazewall/portal/worker-bundles/../worker.zip", "ab".repeat(32))
            }

        assertTrue(failure.message!!.contains("bundle root"), failure.message)
    }

    private fun sampleManifest(): PortalServiceManifest =
        PortalServiceManifest(
            serviceId = PortalServiceId("pdf-parser"),
            contractName = "example.DocumentParser",
            implementationName = "example.PdfDocumentParser",
            dispatcherName = "example.DocumentParserPortalDispatcher",
            schemaId = "sha256:abc123",
            isolationGroup = "document-processing",
            instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
            policyProfile = "restricted-parser",
            workerArtifact = "document-parser.portal-worker.jar",
        )
}
