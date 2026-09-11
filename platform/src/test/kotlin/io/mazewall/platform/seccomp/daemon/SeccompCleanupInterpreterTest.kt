package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.OpenFlags
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.openPath
import io.mazewall.ffi.networking.SeccompConnection
import io.mazewall.platform.daemon.UnixListenDaemonEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CancellationException

@ExtendWith(io.mazewall.core.ForeignFdGuard::class)
@org.junit.jupiter.api.parallel.Isolated
class SeccompCleanupInterpreterTest {
    private val ownedDescriptors = mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()

    private fun <R : FileDescriptorRole> owned(role: R): FileDescriptor<R, FdState.Open, FdOwnership.Owned> =
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                when (val result = openPath("/dev/null", OpenFlags.RDONLY)) {
                    is LinuxNative.SyscallResult.Success ->
                        TestFileDescriptors
                        .adopt(result.value.toInt(), role)
                        .also { ownedDescriptors += it }
                    is LinuxNative.SyscallResult.Error -> error("Cannot open owned test descriptor: $result")
                }
            }
        }

    @AfterEach
    fun closeOwnedFixtures() {
        ownedDescriptors.forEach(RealSocketManager::close)
    }

    @Test
    fun `resource outcome is recorded before cancellation and interrupt restoration`() {
        val interruption = InterruptedException("release interrupted")
        val cancellation = CancellationException("release cancelled")
        val releaseFailure = IllegalStateException("arena release failed")
        val recorded = mutableListOf<SeccompCleanupOutcome>()
        try {
            assertSame(
                cancellation,
                assertThrows(CancellationException::class.java) {
                SeccompCleanupInterpreter.closeResources(
                    listOf({ throw interruption }, { throw cancellation }, { throw releaseFailure }),
                    { outcome ->
                        assertFalse(Thread.currentThread().isInterrupted)
                        recorded += outcome
                    },
                )
            },
            )
            assertTrue(Thread.currentThread().isInterrupted)
            val outcome = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, recorded.single())
            assertEquals(listOf(interruption, cancellation, releaseFailure), outcome.failures)
            assertEquals(setOf(interruption, releaseFailure), cancellation.suppressed.toSet())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `worker cleanup persists cancellation and listener failure after propagation and retry`() {
        val socket = owned(FileDescriptorRole.UnixSocket)
        val listener = owned(FileDescriptorRole.SeccompNotif)
        val cancellation = CancellationException("socket cancellation")
        val listenerFailure = IllegalStateException("listener close failed")
        var retry = false
        val manager = object : SocketManager by CleanupSocketManager({ fd ->
            if (!retry) {
                if (fd == socket) throw cancellation
                throw listenerFailure
            }
        }) {
            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>) = listener
        }
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/cleanup-test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            engine = MockNativeEngine().apply { onPoll = { _, _, _ -> LinuxNative.SyscallResult.Success(1L) } },
            socketManager = manager,
            handshakeWriter = { _, _, _ -> LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(NativeConstants.EPERM, -1L) },
        )
        daemon.clientSockets.add(socket)

        assertSame(cancellation, assertThrows(CancellationException::class.java) { daemon.handleConnection(socket) })
        val failed = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, daemon.cleanupOutcome)
        assertEquals(setOf(cancellation, listenerFailure), failed.failures.toSet())
        assertEquals(setOf(socket, listener), failed.retryableOwnership.toSet())
        retry = true
        daemon.retryCleanup()
        val retried = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, daemon.cleanupOutcome)
        assertEquals(setOf(cancellation, listenerFailure), retried.failures.toSet())
        assertTrue(retried.retryableOwnership.isEmpty())
    }

    @Test
    fun `shutdown cleanup persists cancellation even when its immediate retry succeeds`() {
        val server = owned(FileDescriptorRole.UnixSocket)
        val client = owned(FileDescriptorRole.UnixSocket)
        val cancellation = CancellationException("server cancellation")
        val clientFailure = IllegalStateException("client close failed")
        var serverAttempts = 0
        var retry = false
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/cleanup-test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            engine = MockNativeEngine(),
            socketManager = CleanupSocketManager { fd ->
                if (fd == server && ++serverAttempts == 1) throw cancellation
                if (fd == client && !retry) throw clientFailure
            },
        )
        daemon.clientSockets.add(client)

        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
            daemon.applyEvent(UnixListenDaemonEvent.AcceptLoopFinished(server))
        },
        )
        retry = true
        daemon.retryCleanup()
        val outcome = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, daemon.cleanupOutcome)
        assertEquals(setOf(cancellation, clientFailure), outcome.failures.toSet())
        assertTrue(outcome.retryableOwnership.isEmpty())
    }

    @Test
    fun `native close error remains observable without retrying a consumed descriptor`() {
        val socket = owned(FileDescriptorRole.UnixSocket)
        var attempts = 0
        val realNative = LinuxNative.snapshot()
        val native = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                assertEquals(socket, fd)
                attempts++
                    realNative.fileSystem.close(fd).getOrThrow("close owned test descriptor")
                    ownedDescriptors.remove(socket)
                    return LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
            }
        },
        )
        val connection = SeccompConnection.Accepted(socket)
        LinuxNative.setEngine(native)
        try {
            // Catches discarding the native error or retrying an integer released by the native close attempt.
            val incomplete = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, SeccompCleanupInterpreter.close(connection))
            assertTrue(incomplete.retryableOwnership.isEmpty())
            assertTrue(
                incomplete.failures
                .single()
                .message!!
                .contains("EPERM"),
            )
            assertEquals(SeccompCleanupOutcome.Complete, SeccompCleanupInterpreter.close(connection))
            assertEquals(1, attempts)
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `daemon records native server close failure without keeping a retry token`() {
        val server = owned(FileDescriptorRole.UnixSocket)
        val realNative = LinuxNative.snapshot()
        var attempts = 0
        val native = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                attempts++
                if (attempts == 1) {
                    realNative.fileSystem.close(fd).getOrThrow("close owned server fixture")
                    ownedDescriptors.remove(server)
                }
                return LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
            }
        },
        )
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/cleanup-test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            engine = native,
        )
        LinuxNative.setEngine(native)
        try {
            daemon.applyEvent(UnixListenDaemonEvent.AcceptLoopFinished(server))
            val incomplete = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, daemon.cleanupOutcome)
            assertTrue(incomplete.retryableOwnership.isEmpty())
            daemon.retryCleanup()
            assertEquals(1, attempts)
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `daemon retains failed connection ownership for shutdown retry`() {
        val attempts = mutableListOf<Int>()
        val socket = owned(FileDescriptorRole.UnixSocket)
        val manager = CleanupSocketManager { fd ->
            attempts += fd.value
            if (attempts.size == 1) throw IllegalStateException("close failed")
        }
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/cleanup-test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            engine = MockNativeEngine(),
            socketManager = manager,
        )
        daemon.clientSockets.add(socket)
        daemon.triggerGlobalShutdown("test")

        daemon.handleConnection(socket)

        // Catches removing the descriptor before interpreting the failed close.
        assertEquals(listOf(socket), daemon.clientSockets.toList())
        val incomplete = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, daemon.cleanupOutcome)
        assertEquals(listOf(socket), incomplete.retryableOwnership)
        assertEquals(1, incomplete.failures.size)
        daemon.retryCleanup()
        assertTrue(daemon.clientSockets.isEmpty())
        assertEquals(listOf(socket.value, socket.value), attempts)
        // Shutdown diagnostics must not erase evidence of the earlier failed attempt.
        assertTrue(assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, daemon.cleanupOutcome).retryableOwnership.isEmpty())
    }

    @Test
    fun `interrupted socket close preserves interruption and still closes listener`() {
        val attempts = mutableListOf<Int>()
        val socket = owned(FileDescriptorRole.UnixSocket)
        val listener = owned(FileDescriptorRole.SeccompNotif)
        val connection = SeccompConnection
            .Accepted(socket)
            .apply {
            socketManager = CleanupSocketManager { fd ->
                attempts += fd.value
                if (fd == socket) throw InterruptedException("interrupted close")
            }
        }.attachFd(listener)
        try {
            connection.closeWithResult()

            // Catches swallowing interruption during best-effort descriptor cleanup.
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf(socket.value, listener.value), attempts)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interpreter closes listener after socket failure and retries only the socket`() {
        val attempts = mutableListOf<Int>()
        val failure = IllegalStateException("socket close failed")
        val socket = owned(FileDescriptorRole.UnixSocket)
        val listener = owned(FileDescriptorRole.SeccompNotif)
        val connection = SeccompConnection
            .Accepted(socket)
            .apply {
            socketManager = CleanupSocketManager { fd ->
                attempts += fd.value
                if (attempts.size == 1) throw failure
            }
        }.attachFd(listener)

        val outcome = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, SeccompCleanupInterpreter.close(connection))
        assertEquals(listOf(failure), outcome.failures)
        assertEquals(listOf(connection.socketFd), outcome.retryableOwnership)
        assertEquals(listOf(socket.value, listener.value), attempts)
        assertEquals(SeccompCleanupOutcome.Complete, SeccompCleanupInterpreter.close(connection))
        assertEquals(listOf(socket.value, listener.value, socket.value), attempts)
        assertEquals(SeccompCleanupOutcome.Complete, SeccompCleanupInterpreter.close(connection))
        assertEquals(listOf(socket.value, listener.value, socket.value), attempts)
    }

    @Test
    fun `daemon records incomplete shutdown while closing independent clients`() {
        val attempts = mutableListOf<Int>()
        val server = owned(FileDescriptorRole.UnixSocket)
        val failedClient = owned(FileDescriptorRole.UnixSocket)
        val successfulClient = owned(FileDescriptorRole.UnixSocket)
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/cleanup-test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            engine = MockNativeEngine(),
            socketManager = CleanupSocketManager { fd ->
                attempts += fd.value
                if (fd != successfulClient) throw IllegalStateException("owned descriptor close failed")
            },
        )
        daemon.clientSockets.addAll(listOf(failedClient, successfulClient))

        daemon.applyEvent(UnixListenDaemonEvent.AcceptLoopFinished(server))

        assertTrue(successfulClient.value in attempts)
        assertEquals(listOf(failedClient), daemon.clientSockets.toList())
        val outcome = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, daemon.cleanupOutcome)
        assertEquals(setOf(server, failedClient), outcome.retryableOwnership.toSet())
        assertTrue(outcome.failures.isNotEmpty())
    }

    @Test
    fun `independent resource failures include both pool releases and arena close`() {
        val attempts = mutableListOf<String>()
        val poolFailure = IllegalStateException("pool release failed")
        val arenaFailure = IllegalStateException("arena close failed")

        val outcome = SeccompCleanupInterpreter.closeResources(
            {
                attempts += "notif"
                throw poolFailure
            },
            { attempts += "resp" },
            {
                attempts += "arena"
                throw arenaFailure
            },
        )

        assertEquals(listOf("notif", "resp", "arena"), attempts)
        val incomplete = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, outcome)
        assertEquals(listOf(poolFailure, arenaFailure), incomplete.failures)
        assertTrue(incomplete.retryableOwnership.isEmpty())
    }

    @Test
    fun `resource cancellation propagates after remaining independent releases`() {
        val cancellation = CancellationException("cancel")
        var arenaClosed = false
        val thrown = assertThrows(CancellationException::class.java) {
            SeccompCleanupInterpreter.closeResources(
                { throw cancellation },
                { arenaClosed = true },
            )
        }
        assertSame(cancellation, thrown)
        assertTrue(arenaClosed)
    }

    @Test
    fun `fatal cleanup error remains primary after every independent release`() {
        val fatal = AssertionError("fatal release failure")
        val laterFailure = IllegalStateException("later release failure")
        val attempts = mutableListOf<String>()

        val thrown = assertThrows(AssertionError::class.java) {
            SeccompCleanupInterpreter.closeResources(
                {
                    attempts += "first"
                    throw fatal
                },
                {
                    attempts += "second"
                    throw laterFailure
                },
                { attempts += "third" },
            )
        }

        assertSame(fatal, thrown)
        assertEquals(listOf("first", "second", "third"), attempts)
        assertEquals(listOf(laterFailure), fatal.suppressed.toList())
    }

    @Test
    fun `fatal-only cleanup is recorded incomplete and recorder failure cannot replace it`() {
        val fatal = AssertionError("fatal release failure")
        val recorderFailure = IllegalStateException("outcome recorder failure")
        val recorded = mutableListOf<SeccompCleanupOutcome>()

        val thrown = assertThrows(AssertionError::class.java) {
            SeccompCleanupInterpreter.closeResources(
                listOf({ throw fatal }),
            ) { outcome ->
                recorded += outcome
                throw recorderFailure
            }
        }

        assertSame(fatal, thrown)
        val incomplete = assertInstanceOf(SeccompCleanupOutcome.Incomplete::class.java, recorded.single())
        assertSame(fatal, incomplete.failures.single().cause)
        assertEquals(listOf(recorderFailure), fatal.suppressed.toList())
    }
}

private class CleanupSocketManager(
    private val closeDescriptor: (FileDescriptor<*, FdState.Open, FdOwnership.Owned>) -> Unit,
) : SocketManager {
    override fun createUnixServer(socketPath: String) = error("unused")

    override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) = error("unused")

    override fun connect(socketPath: String) = error("unused")

    override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>) = error("unused")

    override fun sendDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
        fdToSend: FileDescriptor<*, FdState.Open, FdOwnership>,
    ) = error("unused")

    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) = closeDescriptor(fd)
}
