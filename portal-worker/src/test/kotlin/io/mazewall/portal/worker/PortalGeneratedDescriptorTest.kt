package io.mazewall.portal.worker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

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
}
