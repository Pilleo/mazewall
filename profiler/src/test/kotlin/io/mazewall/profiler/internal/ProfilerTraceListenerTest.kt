package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.MockNativeMemory
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.*
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProfilerTraceListenerTest {
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
                    .PathResolutionFailure(0, io.mazewall.profiler.engine.PathResolutionFailure.Reason.DirFdUnresolved, "dirfd=9"),
                ),
            ),
        )
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
        ProfilerTraceListener(
            socketFd = FileDescriptor.replace<FileDescriptorRole.UnixSocket>(308),
            accumulatedLogs = logs,
            stackTracesMap = null,
            pathCache = mutableMapOf(),
            threadFor = { null },
            native = native,
        ).use { listener ->
            listener.start(CountDownLatch(1))
            org.junit.jupiter.api.Assertions
                .assertTrue(eof.await(3, TimeUnit.SECONDS))
        }
        assertEquals(2, acknowledgements.get())
        assertEquals(resolutions, logs.map { it.pathResolution })
    }

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
            val socketFd = FileDescriptor.replace<FileDescriptorRole.UnixSocket>(300)
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
            val socketFd = FileDescriptor.replace<FileDescriptorRole.UnixSocket>(301)
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
            val socketFd = FileDescriptor.replace<FileDescriptorRole.UnixSocket>(302)
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
            val socketFd = FileDescriptor.replace<FileDescriptorRole.UnixSocket>(303)
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
            val socketFd = FileDescriptor.replace<FileDescriptorRole.UnixSocket>(304)
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
