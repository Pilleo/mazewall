package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.MockNativeMemory
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.memory.*
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.profiler.EbpfLoad
import io.mazewall.profiler.PathResolutionQuality
import io.mazewall.profiler.ProfileEnvironment
import io.mazewall.profiler.ProfileObservation
import io.mazewall.profiler.ProfileStrategy
import io.mazewall.profiler.ProfilingCoverage
import io.mazewall.profiler.StackAttribution
import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProfilerTraceListenerTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `invalid wire tid closes without publishing or acknowledging an event`() {
        val input = java.io.ByteArrayInputStream(byteArrayOf(0xAC.toByte(), 0, 0, 0, 0))
        val writes = AtomicInteger()
        val closes = AtomicInteger()
        val logs = mutableListOf<TraceEvent>()
        val native = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val bytes = input.readNBytes(count.toInt())
                    bytes.forEachIndexed { index, byte -> buf.writeByte(index.toLong(), byte) }
                    return LinuxNative.SyscallResult.Success(bytes.size.toLong())
                }

                override fun write(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    writes.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(count)
                }
            },
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
        )
        ProfilerTraceListener(TestFileDescriptors.replace(309), logs, null, mutableMapOf(), { null }, native).use { listener ->
            listener.start(CountDownLatch(1))
            assertTrue(listener.workerTerminatedLatch.await(3, TimeUnit.SECONDS))
            assertEquals(0, writes.get())
            assertEquals(1, closes.get())
            assertTrue(logs.isEmpty())
            assertFalse(listener.drainComplete)
        }
        assertEquals(1, closes.get())
    }

    @Test
    fun `processing failure remains primary when ACK and close also fail`() {
        for (original in listOf(IllegalStateException("capture failed"), LinkageError("fatal capture"))) {
            val closes = AtomicInteger()
            val native = MockNativeEngine(
                memory = object : MockNativeMemory() {
                    override fun write(
                        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                        buf: ManagedSegment,
                        count: Long,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(32, -1L)
                },
                fileSystem = object : MockNativeFileSystem() {
                    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        closes.incrementAndGet()
                        return LinuxNative.SyscallResult.Error(5, -1L)
                    }
                },
            )
            val listener = ProfilerTraceListener(TestFileDescriptors.replace(308), mutableListOf(), mutableMapOf(), mutableMapOf(), { throw original }, native)
            val event = TraceEvent.fromSyscallEvent(
                io.mazewall.profiler.engine.SyscallEvent.fromResolution<io.mazewall.profiler.engine.SyscallEventState.Resolved>(
                io.mazewall.core.Tid(7),
                    "GETPID",
                    emptyList(),
                    io.mazewall.profiler.engine.PathResolution.NotApplicable,
            ),
            )
            val process = ProfilerTraceListener::class.java.getDeclaredMethod("processEvent", TraceEvent::class.java).apply { isAccessible = true }
            val thrown = org.junit.jupiter.api.Assertions
                .assertThrows(java.lang.reflect.InvocationTargetException::class.java) { process.invoke(listener, event) }
            org.junit.jupiter.api.Assertions
                .assertSame(original, thrown.cause)
            assertEquals(1, original.suppressed.size)
            assertEquals(
                1,
                original.suppressed
                .single()
                .suppressed.size,
            )
            listener.closeWithResult()
            assertEquals(1, closes.get())
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = [-1, 0, 2])
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `failed ACK closes endpoint before reading another event`(written: Long) {
        val frame = TraceEventEncoder.encode(
            io.mazewall.profiler.engine.SyscallEvent.fromResolution<io.mazewall.profiler.engine.SyscallEventState.Resolved>(
                io.mazewall.core.Tid(7),
                "GETPID",
                emptyList(),
                io.mazewall.profiler.engine.PathResolution.NotApplicable,
            ),
        )
        val input = java.io.ByteArrayInputStream(byteArrayOf(0xAC.toByte()) + frame + frame)
        val ackAttempts = AtomicInteger()
        val closes = AtomicInteger()
        val native = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val bytes = input.readNBytes(count.toInt())
                    bytes.forEachIndexed { index, byte -> buf.writeByte(index.toLong(), byte) }
                    return LinuxNative.SyscallResult.Success(bytes.size.toLong())
                }

                override fun write(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    ackAttempts.incrementAndGet()
                    return if (written < 0) LinuxNative.SyscallResult.Error(32, -1L) else LinuxNative.SyscallResult.Success(written)
                }
            },
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
        )
        ProfilerTraceListener(TestFileDescriptors.replace(308), mutableListOf(), null, mutableMapOf(), { null }, native).use { listener ->
            listener.start(CountDownLatch(1))
            assertTrue(listener.workerTerminatedLatch.await(3, TimeUnit.SECONDS))
            assertEquals(1, ackAttempts.get(), "ACK failure must stop before the second buffered frame")
            assertEquals(1, closes.get())
            assertTrue(listener.ackWriteFailure != null)
            assertFalse(listener.drainComplete)
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `duplicate paths preserve distinct failed operands and ACK every event`() {
        val resolutions = listOf(
            io.mazewall.profiler.engine.PathResolution.Partial(
                listOf("/tmp/a"),
                listOf(
                    io.mazewall.profiler.engine
                    .PathResolutionFailure(1, io.mazewall.profiler.engine.PathResolutionFailure.Reason.VmReadDenied, "Yama denied"),
                ),
            ),
            io.mazewall.profiler.engine.PathResolution.Partial(
                listOf("/tmp/a"),
                listOf(
                    io.mazewall.profiler.engine
                    .PathResolutionFailure(0, io.mazewall.profiler.engine.PathResolutionFailure.Reason.Truncated, "no NUL in 16 bytes"),
                ),
            ),
        )
        val collected = collectPathEvidence(resolutions)
        assertEquals(2, collected.acknowledgements)
        assertEquals(resolutions, collected.logs.map { it.pathResolution })
        assertEquals(2, collected.stacks.size, "Distinct failures must survive the listener's stack-profile map")
        val coverage = listenerCoverage(collected.stacks.keys.map { ProfileObservation.fromTraceEvent(it) })
        assertEquals(PathResolutionQuality.MIXED, coverage.pathResolution)
        assertEquals(resolutions.flatMap { it.failures }, coverage.pathFailures)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `resolved path cache preserves per-operand ordering`() {
        val paths = listOf(listOf("/tmp/a", "/tmp/b"), listOf("/tmp/b", "/tmp/a"))
        val collected = collectPathEvidence(
            paths.map {
            io.mazewall.profiler.engine.PathResolution
            .Resolved(it)
        },
        )
        assertEquals(2, collected.acknowledgements)
        assertEquals(paths, collected.logs.map { it.paths })
    }

    private data class CollectedPathEvidence(
        val logs: List<TraceEvent>,
        val stacks: Map<TraceEvent, MutableList<Array<StackTraceElement>>>,
        val acknowledgements: Int,
    )

    private fun collectPathEvidence(resolutions: List<io.mazewall.profiler.engine.PathResolution>): CollectedPathEvidence {
        val bytes = byteArrayOf(0xAC.toByte()) +
            resolutions
                .flatMap { resolution ->
            TraceEventEncoder
                .encode(
                io.mazewall.profiler.engine.SyscallEvent
                    .fromResolution<io.mazewall.profiler.engine.SyscallEventState.Resolved>(io.mazewall.core.Tid(7), "RENAME", listOf(100L, 200L), resolution),
            ).toList()
        }.toByteArray()
        val input = java.io.ByteArrayInputStream(bytes)
        val eof = CountDownLatch(1)
        val acknowledgements = AtomicInteger()
        val native = MockNativeEngine(
            memory = object : MockNativeMemory() {
            override fun read(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val chunk = input.readNBytes(count.toInt())
                chunk.forEachIndexed { index, byte -> buf.writeByte(index.toLong(), byte) }
                if (chunk.isEmpty()) eof.countDown()
                return LinuxNative.SyscallResult.Success(chunk.size.toLong())
            }

            override fun write(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (count == 1L && buf.readByte(0L) == 0xAC.toByte()) acknowledgements.incrementAndGet()
                return LinuxNative.SyscallResult.Success(count)
            }
        },
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<TraceEvent>()
        val stacks = mutableMapOf<TraceEvent, MutableList<Array<StackTraceElement>>>()
        ProfilerTraceListener(
            socketFd = TestFileDescriptors
                .replace<FileDescriptorRole.UnixSocket>(308),
            accumulatedLogs = logs,
            stackTracesMap = stacks,
            pathCache = mutableMapOf(),
            threadFor = { null },
            native = native,
        ).use { listener ->
            listener.start(CountDownLatch(1))
            org.junit.jupiter.api.Assertions
                .assertTrue(eof.await(3, TimeUnit.SECONDS))
        }
        return CollectedPathEvidence(logs, stacks, acknowledgements.get())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `unsupported event version ACKs before rejection during graceful close`() {
        assertRejectedEventSettled(malformedEvent(version = 99))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `invalid resolution tag ACKs before rejection during graceful close`() {
        assertRejectedEventSettled(malformedEvent(tag = 99))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `malformed diagnostic ACKs before rejection during graceful close`() {
        assertRejectedEventSettled(malformedEvent())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `invalid tid during graceful drain remains incomplete without an ACK`() {
        assertRejectedEventSettled(byteArrayOf(0, 0, 0, 0), expectedAck = false)
    }

    private fun malformedEvent(
        version: Int = 1,
        tag: Int = 3,
    ): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { output ->
            output.writeInt(7)
            output.writeInt(version)
            output.writeInt(4)
            output.writeBytes("OPEN")
            output.writeInt(0)
            output.writeInt(0)
            output.writeByte(tag)
            output.writeInt(1)
            output.writeInt(0)
            output.writeByte(0)
            output.writeInt(1)
            output.writeByte('x'.code)
            output.writeByte(2) // Neither absent nor present: reject this diagnostic.
        }
        return bytes.toByteArray()
    }

    private fun assertRejectedEventSettled(
        frame: ByteArray,
        expectedAck: Boolean = true,
    ) {
        val timeline = java.util.concurrent.CopyOnWriteArrayList<String>()
        val drainRequested = CountDownLatch(1)
        val reads = AtomicInteger()
        val input = java.io.ByteArrayInputStream(frame)
        val native = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    timeline.add("close")
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val chunk = if (reads.getAndIncrement() == 0) {
                        byteArrayOf(0xAC.toByte())
                    } else {
                        check(drainRequested.await(3, TimeUnit.SECONDS))
                        input.readNBytes(count.toInt())
                    }
                    chunk.forEachIndexed { index, byte -> buf.writeByte(index.toLong(), byte) }
                    return LinuxNative.SyscallResult.Success(chunk.size.toLong())
                }

                override fun write(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val command = buf.readByte(0L).toInt() and 0xff
                    timeline.add("write:$command")
                    if (command == 0x54) drainRequested.countDown()
                    return LinuxNative.SyscallResult.Success(count)
                }
            },
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<TraceEvent>()
        val listener = ProfilerTraceListener(
            TestFileDescriptors
                .replace<FileDescriptorRole.UnixSocket>(309),
            logs,
            null,
            mutableMapOf(),
            { null },
            native,
        )
        val ready = CountDownLatch(1)
        listener.start(ready)
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        listener.close()
        val expectedTimeline = if (expectedAck) listOf("write:84", "write:172", "close") else listOf("write:84", "close")
        assertEquals(expectedTimeline, timeline)
        assertTrue(logs.isEmpty(), "Malformed evidence must not become an observation")
        assertFalse(listener.drainComplete, "A protocol rejection is not a completed drain")
        assertEquals(1, listener.droppedEvents)
        assertFalse(listenerCoverage(emptyList(), listener.droppedEvents, listener.drainComplete).complete)
        listener.close()
        assertEquals(expectedTimeline, timeline, "Repeated cleanup must not acknowledge or close twice")
        assertEquals(1, listener.droppedEvents)
        assertFalse(listener.drainComplete)
    }

    private fun listenerCoverage(
        observations: List<ProfileObservation>,
        dropped: Int = 0,
        drained: Boolean = true,
    ): ProfilingCoverage =
        ProfilingCoverage.infer(
        ProfileStrategy.USER_NOTIF,
            "listener evidence",
            false,
            observations,
            StackAttribution.CAPTURED,
        dropped,
            drained,
            ProfileEnvironment("test", EbpfLoad.Denied("test")),
    )

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `close should close the socket exactly once on graceful drain`() {
        val closeCount = AtomicInteger(0)
        val readLatch = CountDownLatch(1)

        val mock = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closeCount.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    readLatch.await(5, TimeUnit.SECONDS)
                    return LinuxNative.SyscallResult.Success(0L)
                }

                override fun write(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    readLatch.countDown()
                    return LinuxNative.SyscallResult.Success(count)
                }
            },
        )

        run {
            val socketFd = TestFileDescriptors
                .replace<FileDescriptorRole.UnixSocket>(300)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = mutableListOf(),
                stackTracesMap = null,
                pathCache = mutableMapOf(),
                threadFor = { null },
                native = mock,
            )

            val readyLatch = CountDownLatch(1)
            listener.start(readyLatch)

            // Close the listener, which unblocks the read latch and drains
            listener.close()

            // Verify close was called exactly once
            assertEquals(1, closeCount.get())
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `worker thread termination closes socket exactly once and prevents double close`() {
        val closeCount = AtomicInteger(0)
        val mock = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closeCount.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    // Simulate EOF immediately
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
        )

        run {
            val socketFd = TestFileDescriptors
                .replace<FileDescriptorRole.UnixSocket>(301)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = mutableListOf(),
                stackTracesMap = null,
                pathCache = mutableMapOf(),
                threadFor = { null },
                native = mock,
            )

            val readyLatch = CountDownLatch(1)
            listener.start(readyLatch)

            readyLatch.await(2, TimeUnit.SECONDS)
            val workerTerminated = listener.workerTerminatedLatch.await(2, TimeUnit.SECONDS)
            org.junit.jupiter.api.Assertions
                .assertTrue(workerTerminated, "Worker thread should terminate cleanly on EOF")

            // Verify worker thread has closed it exactly once
            assertEquals(1, closeCount.get())

            // Calling close on listener now should be a no-op and not close it again
            listener.close()

            // Verify total close calls remains 1
            assertEquals(1, closeCount.get())
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `test that trace events are successfully streamed into the channel and processed asynchronously`() {
        val accumulatedLogs = mutableListOf<TraceEvent>()
        val stackTracesMap = mutableMapOf<TraceEvent, MutableList<Array<StackTraceElement>>>()

        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                private var callCount = 0

                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (callCount == 0) {
                        buf.writeByte(0L, 0xAC.toByte())
                        callCount++
                        return LinuxNative.SyscallResult.Success(1L)
                    } else if (callCount == 1) {
                        var offset = 0L

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(42))
                        offset += 4

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(1))
                        offset += 4

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(4))
                        offset += 4

                        buf.writeByte(offset, 'O'.code.toByte())
                        buf.writeByte(offset + 1, 'P'.code.toByte())
                        buf.writeByte(offset + 2, 'E'.code.toByte())
                        buf.writeByte(offset + 3, 'N'.code.toByte())
                        offset += 4

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(0))
                        offset += 4

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(1))
                        offset += 4

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(5))
                        offset += 4

                        buf.writeByte(offset, 'h'.code.toByte())
                        buf.writeByte(offset + 1, 'e'.code.toByte())
                        buf.writeByte(offset + 2, 'l'.code.toByte())
                        buf.writeByte(offset + 3, 'l'.code.toByte())
                        buf.writeByte(offset + 4, 'o'.code.toByte())
                        offset += 5

                        buf.writeByte(offset, 1)
                        offset += 1
                        buf.writeIntUnaligned(offset, 0)
                        offset += 4

                        callCount++
                        return LinuxNative.SyscallResult.Success(offset)
                    } else {
                        return LinuxNative.SyscallResult.Success(0L)
                    }
                }
            },
        )

        run {
            val socketFd = TestFileDescriptors
                .replace<FileDescriptorRole.UnixSocket>(302)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = accumulatedLogs,
                stackTracesMap = stackTracesMap,
                pathCache = mutableMapOf(),
                threadFor = { null },
                native = mock,
            )

            val eventCollectedLatch = CountDownLatch(1)
            listener.onEventCollected = { eventCollectedLatch.countDown() }

            val readyLatch = CountDownLatch(1)
            listener.start(readyLatch)
            readyLatch.await(2, TimeUnit.SECONDS)

            val collected = eventCollectedLatch.await(5, TimeUnit.SECONDS)
            org.junit.jupiter.api.Assertions
                .assertTrue(collected, "Event should be collected by collector thread")

            listener.close()
            val collectorTerminated = listener.collectorTerminatedLatch.await(2, TimeUnit.SECONDS)
            org.junit.jupiter.api.Assertions
                .assertTrue(collectorTerminated, "Collector thread should terminate cleanly on close")

            assertEquals(1, accumulatedLogs.size)
            assertEquals("OPEN", accumulatedLogs[0].syscallName)
            assertEquals(listOf("hello"), accumulatedLogs[0].paths)
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `close should be safe and idempotent when called concurrently`() {
        val closeCount = AtomicInteger(0)
        val mock = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closeCount.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
        )

        run {
            val socketFd = TestFileDescriptors
                .replace<FileDescriptorRole.UnixSocket>(303)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = mutableListOf(),
                stackTracesMap = null,
                pathCache = mutableMapOf(),
                threadFor = { null },
                native = mock,
            )

            // Concurrently invoke close() multiple times to ensure idempotency is strictly enforced
            val threads = List(10) {
                Thread {
                    listener.close()
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // Double close on the listener directly should also be safe
            listener.close()

            assertEquals(1, closeCount.get())
        }
    }

    @Test
    fun `idempotency guard prevents double close even under multiple sequential and asynchronous close attempts`() {
        val closeCount = AtomicInteger(0)
        val mock = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closeCount.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
        )

        run {
            val socketFd = TestFileDescriptors
                .replace<FileDescriptorRole.UnixSocket>(304)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = mutableListOf(),
                stackTracesMap = null,
                pathCache = mutableMapOf(),
                threadFor = { null },
                native = mock,
            )

            // Trigger close across all possible sequences:
            // 1. Explicit listener.close()
            listener.close()
            // 2. Sequential call to close()
            listener.close()
            // 3. Sequential call to passThrough() which also closes the socket
            listener.passThrough()

            // Verify the native file system close was called exactly once
            assertEquals(1, closeCount.get())
        }
    }
}
