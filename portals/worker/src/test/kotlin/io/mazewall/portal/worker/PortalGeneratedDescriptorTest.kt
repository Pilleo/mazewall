package io.mazewall.portal.worker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortalGeneratedDescriptorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `descriptor is required before implementation construction`() {
        val builder = PortalDispatcherTable.Builder()
        assertFailsWith<IllegalArgumentException> {
            builder.bootstrapGenerated("${PublishedTableEchoService::class.java.name}=${PublishedTableEchoServiceImpl::class.java.name}", javaClass.classLoader)
        }
    }

    @Test
    fun `worker descriptor selects dispatcher from explicit loader`() {
        val resource = directory.resolve("META-INF/mazewall/portal/${PublishedTableEchoService::class.java.name}")
        java.nio.file.Files
            .createDirectories(resource.parent)
        java.nio.file.Files
            .writeString(resource, PublishedTableEchoServicePortalDispatcher::class.java.name + "\n")
        URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            val builder = PortalDispatcherTable.Builder()
            builder.bootstrapGenerated("${PublishedTableEchoService::class.java.name}=${PublishedTableEchoServiceImpl::class.java.name}", loader)
            assertContentEquals(
                byteArrayOf(9),
                builder.build().dispatchOrNull(
                    io.mazewall.portal.PortalMethod
                .Generated(1100),
                    byteArrayOf(9),
                    emptyList(),
                ),
            )
        }
    }

    @Test
    fun `conflicting and oversized descriptors cannot publish handlers`() {
        val service = PublishedTableEchoService::class.java.name
        val resource = directory.resolve("META-INF/mazewall/portal/$service")
        java.nio.file.Files
            .createDirectories(resource.parent)
        java.nio.file.Files
            .writeString(resource, PublishedTableEchoServicePortalDispatcher::class.java.name)
        URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            assertFailsWith<IllegalArgumentException> {
                PortalDispatcherTable.Builder().bootstrapGenerated("$service=${PublishedTableEchoServiceImpl::class.java.name};wrong.Dispatcher", loader)
            }
            java.nio.file.Files
                .writeString(resource, "A".repeat(4097))
            assertFailsWith<IllegalArgumentException> {
                PortalDispatcherTable.Builder().bootstrapGenerated("$service=${PublishedTableEchoServiceImpl::class.java.name}", loader)
            }
        }
    }

    @Test
    fun `manifest resolves its generated dispatcher before implementation loading`() {
        val root = directory.resolve("META-INF/mazewall/portal")
        root.resolve("services").createDirectories()
        root.resolve("services.index").writeText("published-echo\n")
        root.resolve("services/published-echo").writeText(
            io.mazewall.portal.PortalServiceManifestCodec.encode(
                io.mazewall.portal.PortalServiceManifest(
                    serviceId = io.mazewall.portal.PortalServiceId("published-echo"),
                    contractName = PublishedTableEchoService::class.java.name,
                    implementationName = PublishedTableEchoServiceImpl::class.java.name,
                    dispatcherName = PublishedTableEchoServicePortalDispatcher::class.java.name,
                    schemaId = "sha256:test",
                    isolationGroup = "published",
                    instanceMode = io.mazewall.portal.PortalInstanceMode.STATEFUL_SINGLETON,
                    policyProfile = "restricted",
                    workerArtifact = "published.portal-worker.jar",
                ),
            ),
        )
        root.resolve(PublishedTableEchoService::class.java.name).writeText(PublishedTableEchoServicePortalDispatcher::class.java.name)

        URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            assertEquals(
                listOf(
                    "${PublishedTableEchoService::class.java.name}=" +
                        "${PublishedTableEchoServiceImpl::class.java.name};${PublishedTableEchoServicePortalDispatcher::class.java.name}",
                ),
                generatedManifestEntries(loader),
            )
            val builder = PortalDispatcherTable.Builder()
            assertEquals(1, bootstrapWorkerDispatchers(builder, "malformed legacy property", loader))
            assertContentEquals(
                byteArrayOf(9),
                builder.build().dispatchOrNull(
                    io.mazewall.portal.PortalMethod
                    .Generated(1100),
                        byteArrayOf(9),
                    emptyList(),
                ),
            )
        }
    }

    @Test
    fun `worker rejects incompatible manifests in one isolation group before descriptor loading`() {
        val root = directory.resolve("META-INF/mazewall/portal")
        root.resolve("services").createDirectories()
        root.resolve("services.index").writeText("first\nsecond\n")
        root.resolve("services/first").writeText(manifest("first", "one.zip"))
        root.resolve("services/second").writeText(manifest("second", "two.zip"))

        URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            val failure = assertFailsWith<IllegalArgumentException> { generatedManifestEntries(loader) }
            assertTrue(failure.message!!.contains("incompatible"), failure.message)
        }
    }

    private fun manifest(
        serviceId: String,
        workerArtifact: String,
    ): String =
        io.mazewall.portal.PortalServiceManifestCodec.encode(
            io.mazewall.portal.PortalServiceManifest(
                serviceId = io.mazewall.portal.PortalServiceId(serviceId),
                contractName = "fixture.$serviceId.Contract",
                implementationName = "fixture.$serviceId.Implementation",
                dispatcherName = "fixture.$serviceId.Dispatcher",
                schemaId = "schema",
                isolationGroup = "same-group",
                instanceMode = io.mazewall.portal.PortalInstanceMode.STATEFUL_SINGLETON,
                policyProfile = "restricted",
                workerArtifact = workerArtifact,
            ),
        )
}
