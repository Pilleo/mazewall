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
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerInstallerStateTest {
    @Test
    fun `test state properties`() {
        val listenerFd = TestFileDescriptors
            .replace<FileDescriptorRole.SeccompNotif>(42)
        val socketFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(43)

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
