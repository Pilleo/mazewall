package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeNetworking
import io.mazewall.MockNativeMemory
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.PollFdSegment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SupervisorDaemonEngineTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `processConnectionStep retries on EINTR during ACK write`() {
        var writeCalls = 0
        val mockEngine = MockNativeEngine()
        mockEngine.memory.onWrite = { _, _, _ ->
            writeCalls++
            if (writeCalls == 1) {
                LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
            } else {
                LinuxNative.SyscallResult.Success(1L)
            }
        }

        val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine)
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11)
        val connection = io.mazewall.ffi.networking.SeccompConnection.FdAttached(socketFd, listenerFd)

        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val pollFd = PollFdSegment.of(arena.allocate(8))
            mockEngine.onPoll = { _, _, _ -> LinuxNative.SyscallResult.Success(1L) }

            val result = engine.processConnectionStep(arena, connection, socketFd, pollFd.managed)

            assertNotNull(result, "processConnectionStep should return non-null on successful retry")
            assertEquals(2, writeCalls)
        }
    }

    @Test
    fun `handleNewConnection retries on EINTR`() {
        var acceptCalls = 0
        val mockEngine = MockNativeEngine()
        mockEngine.networking.onAccept4 = { _, _, _, _ ->
            acceptCalls++
            if (acceptCalls == 1) {
                LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
            } else {
                // Fail with something else to stop the loop after success if we don't want to deal with executor
                LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
            }
        }

        val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine)
        val serverFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(5)

        engine.handleNewConnection(serverFd)

        assertEquals(2, acceptCalls)
    }

    @Test
    fun `handleNewConnection retries on EINTR and then successfully accepts client connection`() {
        var acceptCalls = 0
        val mockEngine = MockNativeEngine()
        mockEngine.networking.onAccept4 = { _, _, _, _ ->
            acceptCalls++
            if (acceptCalls == 1) {
                LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
            } else {
                LinuxNative.SyscallResult.Success(12L) // Client socket FD 12
            }
        }
        // Force processConnectionStep to fail and return null to immediately exit handleConnection thread
        mockEngine.onPoll = { _, _, _ ->
            LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
        }

        val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine)
        val serverFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(5)

        engine.handleNewConnection(serverFd)

        assertEquals(2, acceptCalls)
    }
}
