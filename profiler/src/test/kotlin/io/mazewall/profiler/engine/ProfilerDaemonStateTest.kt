package io.mazewall.profiler.engine

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerDaemonStateTest {

    @Test
    fun `test state transitions`() {
        val serverFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(123)
        val uninitialized = ProfilerDaemonState.Uninitialized

        val listening = uninitialized.listening(serverFd, "/tmp/test.sock")
        assertTrue(listening is ProfilerDaemonState.Listening)
        assertEquals("/tmp/test.sock", listening.socketPath)
        assertEquals(serverFd, listening.serverFd)

        val active = listening.active()
        assertTrue(active is ProfilerDaemonState.Active)
        assertEquals(serverFd, active.serverFd)
    }
}
