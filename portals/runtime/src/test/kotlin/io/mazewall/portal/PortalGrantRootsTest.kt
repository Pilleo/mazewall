package io.mazewall.portal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortalGrantRootsTest {
    @Test
    fun `roots are validated once and selected only by their startup id`() {
        val root = Files.createTempDirectory("portal-grant-root-")
        try {
            val configured = PortalGrantRoots.of(PortalGrantRoot(PortalGrantRootId("config"), root))
            assertEquals(root.toRealPath(), configured.directory(PortalGrantRootId("config")))

            val broker = ProcessBroker(grantRoots = configured)
            assertFailsWith<IllegalArgumentException> {
                broker.openReadOnly(PortalGrantRootId("unknown"), "settings.conf")
            }
        } finally {
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun `grant root rejects a non-directory at startup`() {
        val file = Files.createTempFile("portal-grant-root-", ".txt")
        try {
            assertFailsWith<IllegalArgumentException> {
                PortalGrantRoots.of(PortalGrantRoot(PortalGrantRootId("file"), file))
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `broker rejects directory grants and every grant after close`() {
        val root = Files.createTempDirectory("portal-grant-root-")
        val rootId = PortalGrantRootId("config")
        val broker = ProcessBroker(grantRoots = PortalGrantRoots.of(PortalGrantRoot(rootId, root)))
        try {
            assertFailsWith<IllegalArgumentException> {
                broker.openReadOnly(rootId, ".")
            }

            broker.close()
            assertFailsWith<IllegalStateException> {
                broker.openReadOnly(rootId, "settings.conf")
            }
        } finally {
            broker.close()
            Files.deleteIfExists(root)
        }
    }
}
