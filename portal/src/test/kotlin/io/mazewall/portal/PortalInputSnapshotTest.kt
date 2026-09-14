package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class PortalInputSnapshotTest {
    @Test
    fun `captures bounded input without closing caller stream`() {
        val source = CloseTrackingStream("hello".encodeToByteArray())
        val snapshot = PortalInputSnapshot.capture(source, 5)

        assertEquals(5, snapshot.size)
        assertArrayEquals("hello".encodeToByteArray(), snapshot.bytes())
        assertDoesNotThrow { source.read() }
        assertEquals(false, source.closed)
    }

    @Test
    fun `rejects input beyond configured limit`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortalInputSnapshot.capture(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2)
        }
    }

    @Test
    fun `spilled snapshot deletes its owned temporary file on close`() {
        val spillDirectory = Files.createTempDirectory("portal-snapshot")
        val snapshot =
            PortalInputSnapshot.capture(
                ByteArrayInputStream("hello".encodeToByteArray()),
                PortalInputSnapshot.Options(maximumBytes = 5, inMemoryThreshold = 2, spillDirectory = spillDirectory),
            )

        assertEquals("hello", snapshot.openStream().readBytes().decodeToString())
        assertEquals(1, Files.list(spillDirectory).use { it.count() })

        snapshot.close()

        assertEquals(0, Files.list(spillDirectory).use { it.count() })
    }

    private class CloseTrackingStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
