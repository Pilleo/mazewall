package io.mazewall.profiler.internal.engine
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.ffi.HandshakeSession
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandshakeSessionTest {
    @Test
    fun `test state transitions`() {
        val listenerFd = TestFileDescriptors
            .replace<FileDescriptorRole.SeccompNotif>(42)
        val active = HandshakeSession.Active(notifId = 1L, listenerFd = listenerFd)

        val acked = active.acknowledged()
        assertTrue(acked is HandshakeSession.Success)
        assertEquals(1L, acked.notifId)

        val failed = active.failed()
        assertTrue(failed is HandshakeSession.Failed)
        assertEquals(1L, failed.notifId)
    }
}
