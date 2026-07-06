package io.mazewall.profiler.engine

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerInstallerStateTest {

    @Test
    fun `test state properties`() {
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(42)
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(43)

        val connecting = ProfilerInstallerState.Connecting(listenerFd)
        assertEquals(listenerFd, connecting.listenerFd)

        val sending = ProfilerInstallerState.SendingDescriptor(listenerFd, socketFd)
        assertEquals(listenerFd, sending.listenerFd)
        assertEquals(socketFd, sending.socketFd)

        val verifying = ProfilerInstallerState.VerifyingAck(listenerFd, socketFd)
        assertEquals(listenerFd, verifying.listenerFd)
        assertEquals(socketFd, verifying.socketFd)

        val active = ProfilerInstallerState.Active(listenerFd, socketFd)
        assertEquals(listenerFd, active.listenerFd)
        assertEquals(socketFd, active.socketFd)

        val failed = ProfilerInstallerState.Failed(RuntimeException("Oops"))
        assertTrue(failed.error is RuntimeException)
        assertEquals("Oops", failed.error.message)

        assertTrue(ProfilerInstallerState.Uninitialized is ProfilerInstallerState)
        assertTrue(ProfilerInstallerState.InstallingBpf is ProfilerInstallerState)
    }
}
