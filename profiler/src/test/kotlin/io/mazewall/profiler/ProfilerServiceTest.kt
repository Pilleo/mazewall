package io.mazewall.profiler

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.NativeEngine
import io.mazewall.NativeFileSystem
import io.mazewall.NativeMemory
import io.mazewall.PolicyDefinition
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.profiler.engine.ProfilerInstallerInterface
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.internal.ProfilerDaemonManager
import io.mazewall.profiler.internal.ProfilerSocket
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@io.mazewall.EnabledIfLinuxAndSupported
class ProfilerServiceTest {
    @Test
    @Timeout(20)
    fun `service retains cleanup failures from a listener acquired after close`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        StreamInstaller {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }.use { stream ->
            val actual = LinuxNative.snapshot()
            val closes = AtomicInteger()
            val native = object : NativeEngine by actual {
                override val fileSystem = object : NativeFileSystem by actual.fileSystem {
                    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        closes.incrementAndGet()
                        actual.fileSystem.close(fd).getOrThrow("close late session fixture")
                        return LinuxNative.SyscallResult.Error(13, -1L)
                    }
                }
            }
            val service = ProfilerService(runtime(stream).copy(native = native))
            val session = service.openSession()
            val callers = Executors.newFixedThreadPool(2)
            val installation = callers.submit { session.installOnCurrentThread() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                callers.submit { service.shutdown() }.get(1, TimeUnit.SECONDS)
                release.countDown()
                assertFailsWith<ExecutionException> { installation.get(5, TimeUnit.SECONDS) }
                val outcome = assertIs<SeccompCleanupOutcome.Incomplete>(session.cleanupOutcome)
                assertTrue(outcome.retryableOwnership.isEmpty())
                assertFailsWith<IllegalStateException> { service.shutdown() }
                assertEquals(outcome, service.cleanupOutcome)
                assertEquals(1, closes.get(), "Late terminal native errors must not lose or reacquire ownership")
            } finally {
                release.countDown()
                callers.shutdown()
                assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
                session.closeWithResult()
            }
        }
    }

    @Test
    @Timeout(20)
    fun `close returns during stalled installation and disposes its late listener`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        StreamInstaller {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }.use { stream ->
            val session = ProfilerService(runtime(stream)).openSession()
            val callers = Executors.newFixedThreadPool(2)
            val installation = callers.submit { session.installOnCurrentThread() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val close = callers.submit(Callable { session.closeWithResult() })
                assertEquals(SeccompCleanupOutcome.Complete, close.get(1, TimeUnit.SECONDS))
                assertNull(session.state.threadFor(Tid(73)))
                release.countDown()
                val failure = assertFailsWith<ExecutionException> { installation.get(5, TimeUnit.SECONDS) }
                assertIs<IllegalStateException>(failure.cause)
                assertEquals(0x54, stream.commands.poll(5, TimeUnit.SECONDS), "A late listener must enter pass-through")
                assertTrue(session.logs.isEmpty(), "A closed session must not publish late observations")
                assertNull(session.state.threadFor(Tid(73)))
                assertEquals(SeccompCleanupOutcome.Complete, session.cleanupOutcome)
            } finally {
                release.countDown()
                callers.shutdown()
                assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
                session.close()
            }
        }
    }

    @Test
    @Timeout(20)
    fun `closing a session unregisters only its own tid mapping`() {
        StreamInstaller().use { firstStream ->
            StreamInstaller().use { secondStream ->
                val first = ProfilerService(runtime(firstStream)).openSession()
                val second = ProfilerService(runtime(secondStream)).openSession()
                try {
                    first.installOnCurrentThread()
                    second.installOnCurrentThread()
                    assertSame(Thread.currentThread(), first.state.threadFor(Tid(73)))
                    assertSame(Thread.currentThread(), second.state.threadFor(Tid(73)))
                    first.close()
                    assertNull(first.state.threadFor(Tid(73)))
                    assertSame(Thread.currentThread(), second.state.threadFor(Tid(73)))
                    secondStream.emit("/tmp/surviving-session")
                    second.close()
                    assertEquals(listOf("/tmp/surviving-session"), second.logs.flatMap { it.paths })
                } finally {
                    first.close()
                    second.close()
                }
            }
        }
    }

    @Test
    fun `session retains terminal native close failure without retrying the consumed descriptor`() {
        StreamInstaller().use { stream ->
            val actual = LinuxNative.snapshot()
            val closes = AtomicInteger()
            val native = object : NativeEngine by actual {
                override val fileSystem = object : NativeFileSystem by actual.fileSystem {
                    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        closes.incrementAndGet()
                        actual.fileSystem.close(fd).getOrThrow("close owned session fixture")
                        return LinuxNative.SyscallResult.Error(13, -1L)
                    }
                }
            }
            val session = ProfilerService(runtime(stream).copy(native = native)).openSession()
            session.installOnCurrentThread()
            val outcome = assertIs<SeccompCleanupOutcome.Incomplete>(session.closeWithResult())
            assertTrue(
                outcome.failures
                .single()
                .message!!
                .contains("errno=13"),
            )
            assertTrue(outcome.retryableOwnership.isEmpty())
            assertEquals(outcome, session.cleanupOutcome)
            assertFailsWith<IllegalStateException> { session.close() }
            assertEquals(1, closes.get())
        }
    }

    @Test
    fun `session persists cancellation before propagating it and can retry a pre-dispatch close`() {
        StreamInstaller().use { stream ->
            val actual = LinuxNative.snapshot()
            val cancellation = CancellationException("cancel before native close")
            var reject = true
            val native = object : NativeEngine by actual {
                override val fileSystem = object : NativeFileSystem by actual.fileSystem {
                    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        if (reject) throw cancellation
                        return actual.fileSystem.close(fd)
                    }
                }
            }
            val service = ProfilerService(runtime(stream).copy(native = native))
            val session = service.openSession()
            session.installOnCurrentThread()
            try {
                assertSame(cancellation, assertFailsWith<CancellationException> { session.close() })
                val outcome = assertIs<SeccompCleanupOutcome.Incomplete>(session.cleanupOutcome)
                assertEquals(listOf(cancellation), outcome.failures)
                assertEquals(1, outcome.retryableOwnership.size)
                assertNull(session.state.threadFor(Tid(73)))
            } finally {
                reject = false
                session.close()
                service.shutdown()
            }
        }
    }

    @Test
    @Timeout(20)
    fun `concurrent services with identical tids retain their own events and shutdown authority`() {
        StreamInstaller().use { firstStream ->
            StreamInstaller().use { secondStream ->
                val first = ProfilerService(runtime(firstStream))
                val second = ProfilerService(runtime(secondStream))
                val entered = CountDownLatch(2)
                val releaseFirst = CountDownLatch(1)
                val releaseSecond = CountDownLatch(1)
                val callers = Executors.newFixedThreadPool(2)
                try {
                    val a = callers.submit(
                        Callable {
                        first.profile {
                            entered.countDown()
                            releaseFirst.await()
                            "first"
                        }
                    },
                    )
                    val b = callers.submit(
                        Callable {
                        second.profile {
                            entered.countDown()
                            releaseSecond.await()
                            "second"
                        }
                    },
                    )
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    firstStream.emit("/tmp/shared")
                    secondStream.emit("/tmp/shared")
                    firstStream.emit("/tmp/first-only")
                    secondStream.emit("/tmp/second-only")
                    releaseFirst.countDown()
                    assertEquals(listOf("/tmp/shared", "/tmp/first-only"), a.get(5, TimeUnit.SECONDS).observations.flatMap { it.paths })

                    first.shutdown()
                    assertEquals(
                        null,
                        secondStream.commands.poll(),
                        "Closing the first service must not send pass-through to the second service",
                    )
                    secondStream.emit("/tmp/second-still-recording")
                    releaseSecond.countDown()
                    assertEquals(
                        listOf("/tmp/shared", "/tmp/second-only", "/tmp/second-still-recording"),
                        b.get(5, TimeUnit.SECONDS).observations.flatMap { it.paths },
                    )
                } finally {
                    releaseFirst.countDown()
                    releaseSecond.countDown()
                    callers.shutdownNow()
                    first.shutdown()
                    second.shutdown()
                }
            }
        }
    }

    private fun runtime(installer: StreamInstaller): ProfilerRuntime {
        val manager = ProfilerDaemonManager(MockNativeEngine(), ProfilerTest.MockSocketManager(), ProfilerTest.MockProcessLauncher())
        val actual = LinuxNative.snapshot()
        val native = object : NativeEngine by actual {
            override val memory = object : NativeMemory by actual.memory {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    assertEquals(installer.listenerFd, fd.value, "A runtime must only read its own listener")
                    return actual.memory.read(fd, buf, count)
                }

                override fun write(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    assertEquals(installer.listenerFd, fd.value, "A runtime must only ACK or drain its own listener")
                    return actual.memory.write(fd, buf, count)
                }
            }
        }
        return ProfilerRuntime({ manager }, installer, { Tid(73) }, native)
    }

    private class StreamInstaller(
        private val beforeInstall: () -> Unit = {},
    ) : ProfilerInstallerInterface,
        AutoCloseable {
        private val directory = Files.createTempDirectory("profiler-service-test-")
        private val path = directory.resolve("trace.sock")
        private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path))
        private lateinit var peer: SocketChannel
        private lateinit var output: DataOutputStream
        private lateinit var commandReader: Thread
        val commands = LinkedBlockingQueue<Int>()
        var listenerFd: Int? = null
            private set

        override fun installProfilingFilterForThread(
            socketPath: String,
            policy: PolicyDefinition<*>,
            accumulatedLogs: MutableList<TraceEvent>,
            stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            pathCache: MutableMap<String, Long>,
            processWide: Boolean,
            startTraceListener: (
                io.mazewall.core.FileDescriptor<io.mazewall.core.FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open, io.mazewall.core.FdOwnership.Owned>,
                MutableList<TraceEvent>,
                MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
                MutableMap<String, Long>,
                CountDownLatch,
            ) -> Unit,
        ) {
            beforeInstall()
            val fd = ProfilerSocket.connectDescriptorWithRetry(path.toString())
            listenerFd = fd.value
            peer = server.accept()
            output = DataOutputStream(Channels.newOutputStream(peer))
            output.writeByte(0xAC)
            output.flush()
            commandReader = Thread {
                val byte = ByteBuffer.allocate(1)
                while (true) {
                    byte.clear()
                    if (peer.read(byte) == -1) break
                    val command = byte.get(0).toInt() and 0xff
                    commands.put(command)
                    if (command == 0x54) {
                        peer.close()
                        break
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val ready = CountDownLatch(1)
            startTraceListener(fd, accumulatedLogs, stackTracesMap, pathCache, ready)
            check(ready.await(5, TimeUnit.SECONDS))
        }

        fun emit(path: String) {
            output.writeInt(73)
            output.writeInt(1) // private profiler event version
            output.writeInt(6)
            output.writeBytes("OPENAT")
            output.writeInt(3)
            output.writeLong(-100L)
            output.writeLong(0L)
            output.writeLong(0L)
            output.writeInt(1)
            output.writeInt(path.length)
            output.writeBytes(path)
            output.writeByte(1) // Resolved
            output.writeInt(0) // failures
            output.flush()
            assertEquals(0xAC, commands.poll(5, TimeUnit.SECONDS), "Every trace event requires its own ACK")
        }

        override fun close() {
            if (::peer.isInitialized) peer.close()
            if (::commandReader.isInitialized) commandReader.join(1000)
            server.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
