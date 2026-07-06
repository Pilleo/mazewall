package io.mazewall.profiler.engine

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandshakeSessionTest {

    @Test
    fun `test state transitions`() {
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(42)
        val active = HandshakeSession.Active(notifId = 1L, listenerFd = listenerFd)

        val acked = active.acknowledged()
        assertTrue(acked is HandshakeSession.Success)
        assertEquals(1L, acked.notifId)

        val failed = active.failed()
        assertTrue(failed is HandshakeSession.Failed)
        assertEquals(1L, failed.notifId)
    }
}
