package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeMemory
import io.mazewall.enforcer.api.MockNativeNetworking
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.socket.SocketManager
import io.mazewall.platform.syscall.*
import io.mazewall.testing.awaitCondition
import io.mazewall.testing.awaitOrFail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Isolated
class SupervisorDaemonEngineTest {
    @Test
    fun `processConnectionStep retries on EINTR during ACK write`() {
        val originalEngine = LinuxNative.engineIdentity
        var writeCalls = 0
        val mockEngine = MockNativeEngine()
        mockEngine.memory.onWrite = { _, _, _ ->
            assertSame(originalEngine, LinuxNative.engineIdentity, "ACK fixture must preserve the process native engine")
            writeCalls++
            if (writeCalls == 1) {
                LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
            } else {
                LinuxNative.SyscallResult.Success(1L)
            }
        }

        val (socketFd, listenerFd) = NativeArena.ofConfined().use { arena ->
            with(arena) {
                val path = arena.allocateFrom("/dev/null")
                val socket = (RealNativeEngine.fileSystem.open(path, OpenFlags.RDONLY) as LinuxNative.SyscallResult.Success).value.toInt()
                val listener = (RealNativeEngine.fileSystem.open(path, OpenFlags.RDONLY) as LinuxNative.SyscallResult.Success).value.toInt()
                TestFileDescriptors
                    .adopt(socket, FileDescriptorRole.UnixSocket) to
                    TestFileDescriptors
                        .adopt(listener, FileDescriptorRole.SeccompNotif)
            }
        }
        try {
            assertEquals("/dev/null", Files.readSymbolicLink(Path.of("/proc/self/fd/${socketFd.value}")).toString())
            assertEquals("/dev/null", Files.readSymbolicLink(Path.of("/proc/self/fd/${listenerFd.value}")).toString())
            val socketManager = TestSocketManager()
            val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine, socketManager = socketManager)
            val connection = io.mazewall.ffi.networking.SeccompConnection
                .FdAttached(socketFd, listenerFd)
                .also { it.socketManager = socketManager }
            NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(8))
                mockEngine.onPoll = { fds, _, _ ->
                    PollFdSegment.of(fds).setRevents(NativeConstants.POLLOUT)
                    LinuxNative.SyscallResult.Success(1L)
                }

                val result = engine.processConnectionStep(arena, connection, socketFd, pollFd.managed)

                assertNotNull(result, "processConnectionStep should return non-null on successful retry")
                assertEquals(1, writeCalls)
            }
        } finally {
            assertTrue(RealNativeEngine.fileSystem.close(socketFd) is LinuxNative.SyscallResult.Success, "owned ACK socket closed")
            assertTrue(RealNativeEngine.fileSystem.close(listenerFd) is LinuxNative.SyscallResult.Success, "owned ACK listener closed")
        }
    }

    @Test
    fun `handleNewConnection retries EINTR but does not replace another accept error with a blocking accept`() {
        var acceptCalls = 0
        var socketManagerAcceptCalls = 0
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

        val socketManager = object : TestSocketManager() {
            override fun accept(
                serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
                socketManagerAcceptCalls++
                return super.accept(serverFd)
            }
        }
        val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine, socketManager = socketManager)
        val serverFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(5)

        engine.handleNewConnection(serverFd)

        assertEquals(2, acceptCalls)
        assertEquals(0, socketManagerAcceptCalls)
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

        val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine, socketManager = TestSocketManager())
        val serverFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(5)

        engine.handleNewConnection(serverFd)

        assertEquals(2, acceptCalls)
    }

    @Test
    fun `handleNewConnection accepts file descriptor zero without falling back`() {
        var fallbackAcceptCalls = 0
        val mockEngine = MockNativeEngine()
        mockEngine.networking.onAccept4 = { _, _, _, _ ->
            LinuxNative.SyscallResult.Success(0L)
        }
        mockEngine.onPoll = { _, _, _ ->
            LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
        }
        val mockSocket = object : TestSocketManager(5) {
            override fun accept(
                serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
                fallbackAcceptCalls++
                return TestFileDescriptors
                    .replace(11)
            }
        }
        val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine, socketManager = mockSocket)
        val serverFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(5)

        engine.handleNewConnection(serverFd)

        assertEquals(0, fallbackAcceptCalls, "accept4 file descriptor zero is a successful result")
    }

    @Test
    fun `handleNewConnection safely closes client socket if executor submit throws Error`() {
        var acceptCalls = 0
        val mockEngine = MockNativeEngine()
        mockEngine.networking.onAccept4 = { _, _, _, _ ->
            acceptCalls++
            if (acceptCalls == 1) {
                LinuxNative.SyscallResult.Success(12L) // Client socket FD 12
            } else {
                LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
            }
        }

        val mockSocket = object : TestSocketManager(5) {}
        val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine, socketManager = mockSocket)
        val serverFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(5)

        // Reflection to inject throwing ExecutorService
        val delegateField = SupervisorDaemonEngine::class.java.getDeclaredField("delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(engine)

        val executorField = io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine::class.java.getDeclaredField("connectionExecutor")
        executorField.isAccessible = true
        executorField.set(
            delegate,
            object : java.util.concurrent.ExecutorService {
            override fun execute(command: Runnable) {
                throw OutOfMemoryError("Simulated OOM")
            }

            override fun submit(task: Runnable): java.util.concurrent.Future<*> {
                throw OutOfMemoryError("Simulated OOM")
            }

            override fun <T> submit(task: java.util.concurrent.Callable<T>): java.util.concurrent.Future<T> {
                throw OutOfMemoryError("Simulated OOM")
            }

            override fun <T> submit(
                task: Runnable,
                result: T,
            ): java.util.concurrent.Future<T> {
                throw OutOfMemoryError("Simulated OOM")
            }

            override fun shutdown() {}

            override fun shutdownNow(): List<Runnable> = emptyList()

            override fun isShutdown(): Boolean = false

            override fun isTerminated(): Boolean = false

            override fun awaitTermination(
                timeout: Long,
                unit: java.util.concurrent.TimeUnit,
            ): Boolean = true

            override fun <T> invokeAll(tasks: Collection<java.util.concurrent.Callable<T>>): List<java.util.concurrent.Future<T>> = emptyList()

            override fun <T> invokeAll(
                tasks: Collection<java.util.concurrent.Callable<T>>,
                timeout: Long,
                unit: java.util.concurrent.TimeUnit,
            ): List<java.util.concurrent.Future<T>> = emptyList()

            override fun <T> invokeAny(tasks: Collection<java.util.concurrent.Callable<T>>): T = throw UnsupportedOperationException()

            override fun <T> invokeAny(
                tasks: Collection<java.util.concurrent.Callable<T>>,
                timeout: Long,
                unit: java.util.concurrent.TimeUnit,
            ): T = throw UnsupportedOperationException()
        },
        )

        var caughtError = false
        try {
            engine.handleNewConnection(serverFd)
        } catch (e: OutOfMemoryError) {
            caughtError = true
            assertEquals("Simulated OOM", e.message)
        }

        assertTrue(caughtError, "OutOfMemoryError should bubble up")
        assertTrue(mockSocket.closedFds.contains(12), "Client socket 12 should be closed when thread creation fails")
        assertEquals(0, engine.clientSockets.size, "Client socket should be removed from tracking list")
    }

    internal open class TestSocketManager(
        val serverFdVal: Int = 5,
    ) : SocketManager {
        val closedFds = CopyOnWriteArrayList<Int>()

        override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
            TestFileDescriptors
            .replace(serverFdVal)

        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
            TestFileDescriptors
                .replace(11)

        override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
            TestFileDescriptors
            .replace(12)

        override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
            closedFds.add(fd.value)
        }

        override fun recvDescriptor(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
        ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>? =
            TestFileDescriptors
            .replace(20)

        override fun sendDescriptor(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
            fdToSend: FileDescriptor<*, FdState.Open, FdOwnership>,
        ): Boolean = true
    }

    @Test
    fun `run closes client sockets and active listeners on exception or break`() {
        val mockEngine = MockNativeEngine()
        val expectedCloses = CountDownLatch(2)

        val mockSocket = object : TestSocketManager(5) {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                super.close(fd)
                if (fd.value == 5 || fd.value == 12) {
                    expectedCloses.countDown()
                }
            }
        }

        // Mock accept4: first call accepts client FD 12, subsequent calls return EINTR
        var acceptCount = 0
        mockEngine.networking.onAccept4 = { _, _, _, _ ->
            acceptCount++
            if (acceptCount == 1) {
                LinuxNative.SyscallResult.Success(12L)
            } else {
                LinuxNative.SyscallResult.Error(11, -1L) // Fix to EAGAIN (11) to break loop if needed, but not strictly necessary here
            }
        }

        var pollCount = 0
        mockEngine.onPoll = { fds, nfds, _ ->
            pollCount++
            if (pollCount == 1) {
                // Outer poll, only server — set POLLIN so handleNewConnection fires
                PollFdSegment.of(fds).setRevents(NativeConstants.POLLIN)
                LinuxNative.SyscallResult.Success(1L)
            } else {
                // Throw to break the loop on second iteration
                throw RuntimeException("Simulated loop interrupt")
            }
        }

        val engine = SupervisorDaemonEngine("/tmp/test.sock", engine = mockEngine, socketManager = mockSocket)

        try {
            engine.run()
        } catch (e: RuntimeException) {
            assertEquals("Simulated loop interrupt", e.message)
        }

        assertTrue(expectedCloses.await(1, TimeUnit.SECONDS), "Server and client sockets should close")

        assertTrue(mockSocket.closedFds.contains(5), "Server socket 5 should be closed")
        assertTrue(mockSocket.closedFds.contains(12), "Client socket 12 should be closed")
    }

    @Test
    fun `daemon remains resilient and accepts consecutive client connections after previous session disconnects`() {
        val client1Accepted = CountDownLatch(1)
        val client1Finished = CountDownLatch(1)
        val client2Accepted = CountDownLatch(1)
        val client2Finished = CountDownLatch(1)
        val shutdownAllowed = CountDownLatch(1)

        val mockEngine = MockNativeEngine()
        val mockSocket = object : TestSocketManager(5) {
            override fun recvDescriptor(
                socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
            ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>? {
                return if (socketFd.value == 12) {
                    TestFileDescriptors
                        .replace(20)
                } else if (socketFd.value == 13) {
                    TestFileDescriptors
                        .replace(21)
                } else {
                    null
                }
            }

            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                super.close(fd)
                if (fd.value == 12) {
                    client1Finished.countDown()
                } else if (fd.value == 13) {
                    client2Finished.countDown()
                }
            }
        }

        var acceptCount = 0
        mockEngine.networking.onAccept4 = { _, _, _, _ ->
            acceptCount++
            if (acceptCount == 1) {
                client1Accepted.countDown()
                LinuxNative.SyscallResult.Success(12L) // Client 1 socket FD 12
            } else if (acceptCount == 2) {
                client2Accepted.countDown()
                LinuxNative.SyscallResult.Success(13L) // Client 2 socket FD 13
            } else {
                LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
            }
        }

        mockEngine.memory.onWrite = { _, _, _ ->
            LinuxNative.SyscallResult.Success(1L)
        }

        // Single-threaded multi-FD reactor poll mock.
        // We distinguish calls by nfds:
        //   nfds==1, fd==5  → initial outer poll (server only); set POLLIN on server slot to trigger accept
        //   nfds==2, fd==5  → outer poll server + one client; set POLLIN ONLY on CLIENT slot (index 1)
        //                     so the client connection progresses without triggering another accept4
        //   nfds==1, fd==12 or 13 → INNER poll inside processConnectionStep(Accepted)
        //   nfds==2, fd==20 or 21 → handleSession poll; set POLLIN on socket slot (index 1) to trigger Shutdown
        mockEngine.onPoll = { fds, nfds, _ ->
            val firstFd = PollFdSegment.of(fds).getFd()

            when {
                firstFd == 5 && nfds == 1L -> {
                    if (acceptCount < 2) {
                        PollFdSegment.of(fds).setRevents(NativeConstants.POLLIN)
                        LinuxNative.SyscallResult.Success(1L)
                    } else {
                        shutdownAllowed.awaitOrFail("test releases idle reactor after both clients finish")
                        LinuxNative.SyscallResult.Success(0L)
                    }
                }
                firstFd == 5 && nfds == 2L -> {
                    // Outer poll, server + active client: set POLLIN only on CLIENT slot so connection advances
                    // Do NOT set POLLIN on server slot — that would trigger another accept4 prematurely
                    PollFdSegment.of(fds.asSlice(8L, 8L)).setRevents(NativeConstants.POLLIN)
                    LinuxNative.SyscallResult.Success(1L)
                }
                (firstFd == 12 || firstFd == 13) && nfds == 1L -> {
                    // Accepted connections wait for POLLIN; attached handshakes wait for POLLOUT.
                    val requested = PollFdSegment.of(fds).getEvents().toInt()
                    PollFdSegment.of(fds).setRevents(
                        if ((requested and NativeConstants.POLLOUT.toInt()) != 0) NativeConstants.POLLOUT else NativeConstants.POLLIN,
                    )
                    LinuxNative.SyscallResult.Success(1L)
                }
                (firstFd == 20 || firstFd == 21) && nfds == 2L -> {
                    // handleSession: set POLLIN on socket slot (index 1) to trigger Shutdown
                    PollFdSegment.of(fds.asSlice(8L, 8L)).setRevents(NativeConstants.POLLIN)
                    LinuxNative.SyscallResult.Success(1L)
                }
                else -> {
                    error("Unexpected reactor poll: firstFd=$firstFd, nfds=$nfds")
                }
            }
        }

        val engine = SupervisorDaemonEngine("/tmp/test_resilient.sock", engine = mockEngine, socketManager = mockSocket)

        // Run the engine in a background thread
        val failure = AtomicReference<Throwable?>()
        val engineThread = Thread {
            try {
                engine.run()
            } catch (caught: Throwable) {
                failure.set(caught)
                }
        }.apply {
            name = "test-supervisor-engine"
            start()
        }

        try {
            // Wait for both clients to be accepted and then finished
            assertTrue(client1Accepted.await(5, TimeUnit.SECONDS), "Client 1 should be accepted")
            assertTrue(client1Finished.await(5, TimeUnit.SECONDS), "Client 1 session should finish and close socket")

            assertTrue(client2Accepted.await(5, TimeUnit.SECONDS), "Client 2 should be accepted")
            assertTrue(client2Finished.await(5, TimeUnit.SECONDS), "Client 2 session should finish and close socket")
        } finally {
            engine.triggerGlobalShutdown("test teardown")
            shutdownAllowed.countDown()
            awaitCondition(description = "supervisor reactor terminated after shutdown") { !engineThread.isAlive }
        }
        failure.get()?.let { throw it }

        // Verify both sockets and their listeners were closed
        assertTrue(mockSocket.closedFds.contains(12), "Client socket 12 should be closed")
        assertTrue(mockSocket.closedFds.contains(20), "Listener socket 20 should be closed")
        assertTrue(mockSocket.closedFds.contains(13), "Client socket 13 should be closed")
        assertTrue(mockSocket.closedFds.contains(21), "Listener socket 21 should be closed")
    }
}
