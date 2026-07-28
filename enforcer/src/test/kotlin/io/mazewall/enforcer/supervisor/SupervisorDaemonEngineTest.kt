package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeNetworking
import io.mazewall.MockNativeMemory
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketManager
import io.mazewall.core.FdState
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.PollFdSegment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    open class TestSocketManager(val serverFdVal: Int = 5) : SocketManager {
        val closedFds = CopyOnWriteArrayList<Int>()

        override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> =
            FileDescriptor.unsafe(serverFdVal)

        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> =
            FileDescriptor.unsafe(11)

        override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> =
            FileDescriptor.unsafe(12)

        override fun close(fd: FileDescriptor<*, FdState.Open>) {
            closedFds.add(fd.value)
        }

        override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? =
            FileDescriptor.unsafe(20)

        override fun sendDescriptor(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
            fdToSend: FileDescriptor<*, FdState.Open>
        ): Boolean = true
    }

    @Test
    fun `run closes client sockets and active listeners on exception or break`() {
        val mockEngine = MockNativeEngine()
        val listenerReadyLatch = CountDownLatch(1)

        val mockSocket = object : TestSocketManager(5) {
            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
                val fd = super.recvDescriptor(socketFd)
                listenerReadyLatch.countDown()
                return fd
            }
        }

        // Mock accept4 to successfully return client FD 12 once
        var acceptCount = 0
        mockEngine.networking.onAccept4 = { _, _, _, _ ->
            acceptCount++
            if (acceptCount == 1) {
                LinuxNative.SyscallResult.Success(12L)
            } else {
                LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
            }
        }

        // Mock poll:
        // First poll on server FD (5): return 1 (ready to accept)
        // Second poll on server FD (5): wait for background thread to receive listener FD, then throw exception to break loop
        // Poll on client FD (12): return 1 to trigger recvDescriptor, then on subsequent calls return 0 to block/keep alive
        var serverPollCount = 0
        var clientPollCount = 0
        mockEngine.onPoll = { fds, nfds, timeout ->
            val pfd = PollFdSegment.of(fds)
            val fd = pfd.getFd()
            if (fd == 5) {
                serverPollCount++
                if (serverPollCount == 1) {
                    LinuxNative.SyscallResult.Success(1L)
                } else {
                    listenerReadyLatch.await(5, TimeUnit.SECONDS)
                    // Give a tiny moment for activeListeners.add to finish executing on the executor thread
                    Thread.sleep(50)
                    throw RuntimeException("Simulated loop interrupt")
                }
            } else if (fd == 12) {
                clientPollCount++
                if (clientPollCount == 1) {
                    LinuxNative.SyscallResult.Success(1L)
                } else {
                    // Let subsequent polls on 12 block/sleep briefly to let the main thread progress and exit
                    Thread.sleep(50)
                    LinuxNative.SyscallResult.Success(0L)
                }
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine, socketManager = mockSocket)

        try {
            engine.run()
        } catch (e: RuntimeException) {
            assertEquals("Simulated loop interrupt", e.message)
        }

        // Wait a little bit for any background thread activities to complete
        Thread.sleep(200)

        // Verify that server socket (5), client socket (12), and listener socket (20) were all closed!
        assertTrue(mockSocket.closedFds.contains(5), "Server socket 5 should be closed")
        assertTrue(mockSocket.closedFds.contains(12), "Client socket 12 should be closed")
        assertTrue(mockSocket.closedFds.contains(20), "Listener socket 20 should be closed")
    }
}
