package io.mazewall.profiler.internal

import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.TraceEvent
import io.mazewall.profiler.api.profilerCleanupOutcome
import io.mazewall.profiler.api.throwIfIncomplete
import io.mazewall.profiler.ffi.ProfilerNativeCommandWriter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Background listener that reads trace events from a daemon socket and resolves them.
 *
 * ARCHITECTURAL INVARIANT: This listener implements [AutoCloseable] to ensure a
 * deterministic lifecycle. Closing the listener ensures that the worker thread is joined
 * and the underlying Unix domain socket is explicitly released, preventing "half-dead"
 * listeners or socket leaks during consecutive profiling runs.
 */
internal class ProfilerTraceListener(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val accumulatedLogs: MutableList<TraceEvent>,
    private val stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
    private val pathCache: MutableMap<String, Long>,
    private val threadFor: (Tid) -> Thread?,
    private val native: NativeEngine,
) : AutoCloseable {
    private val logger = Logger.getLogger(ProfilerTraceListener::class.java.name)
    private val closed = AtomicBoolean(false)

    // Thread-safe idempotent close guard to prevent native double-close.
    private val socketClosed = AtomicBoolean(false)
    private val socketCloseLock = Any()

    @Volatile
    private var socketCleanupOutcome: SeccompCleanupOutcome = SeccompCleanupOutcome.Complete

    @Volatile
    private var threadCleanupOutcome: SeccompCleanupOutcome = SeccompCleanupOutcome.Complete

    val cleanupOutcome: SeccompCleanupOutcome
        get() = when (val threads = threadCleanupOutcome) {
            SeccompCleanupOutcome.Complete -> socketCleanupOutcome
            is SeccompCleanupOutcome.Incomplete -> profilerCleanupOutcome(listOf(socketCleanupOutcome, threads), retryableOwnership())
        }
    private var workerThread: Thread? = null
    private var collectorThread: Thread? = null

    private enum class CollectionOutcome { COLLECTING, DRAINED, FAILED }

    @Volatile private var collectionOutcome = CollectionOutcome.COLLECTING

    /**
     * Closes the underlying socket file descriptor exactly once in a thread-safe and idempotent manner.
     *
     * Since [FileDescriptor] is immutable and calling [FileDescriptor.close] returns a closed copy without
     * mutating the original reference, the standard [FileDescriptor.isClosedType] checks are insufficient
     * when close is invoked concurrently or sequentially across different lifecycle phases (e.g., worker
     * thread shutdown vs listener close).
     *
     * This helper uses the [socketClosed] [AtomicBoolean] guard to ensure that [FileDescriptor.close] (and
     * the underlying native close system call) is executed at most once, eliminating native double-close
     * vulnerabilities and preventing file descriptor recycling corruption inside the JVM.
     */
    @Suppress("TooGenericExceptionCaught") // Persist pre-dispatch native dependency failures for the session owner.
    private fun closeSocketOnce() {
        synchronized(socketCloseLock) {
            if (socketClosed.get()) return
            try {
                val result = native.fileSystem.close(socketFd)
                // A returned native error may already have released the integer. Never retry it.
                socketClosed.set(true)
                socketCleanupOutcome = when (result) {
                    is LinuxNative.SyscallResult.Success -> SeccompCleanupOutcome.Complete
                    is LinuxNative.SyscallResult.Error -> SeccompCleanupOutcome.Incomplete(
                        listOf(IllegalStateException("close(fd=${socketFd.value}) failed: errno=${result.errno}")),
                        emptyList(),
                    )
                }
            } catch (failure: Exception) {
                recordCloseFailure(failure)
            } catch (failure: Error) {
                recordCloseFailure(IllegalStateException("Profiler listener socket cleanup aborted", failure))
                throw failure
            }
        }
    }

    private fun recordCloseFailure(failure: Exception) {
        // Native engines retire the generation before dispatch. An exception alone cannot
        // prove retryability, and a retired token must never be adopted or closed again.
        if (!socketFd.isLiveForIo()) socketClosed.set(true)
        socketCleanupOutcome = SeccompCleanupOutcome.Incomplete(listOf(failure), retryableOwnership())
    }

    fun retryableOwnership(): List<FileDescriptor<*, FdState.Open, FdOwnership.Owned>> =
        synchronized(socketCloseLock) {
            if (socketClosed.get() || !socketFd.isLiveForIo()) emptyList() else listOf(socketFd)
        }

    internal val eventQueue = TraceEventQueue()
    val eventChannel = eventQueue.channel
    val eventFlow: Flow<TraceEvent> = eventChannel.receiveAsFlow()

    internal val workerTerminatedLatch = CountDownLatch(1)
    internal val collectorTerminatedLatch = CountDownLatch(1)

    internal var onEventCollected: ((TraceEvent) -> Unit)? = null

    var state: TraceListenerState = TraceListenerState.Disconnected
        private set

    /** True only after a complete terminal record and physical reader/collector retirement. */
    @Volatile
    var drainComplete: Boolean = false
        private set

    @Volatile
    var droppedEvents: Int = 0
        private set

    @Volatile
    internal var ackWriteFailure: TraceCommandWriteResult? = null
        private set

    private val gracefulDrainRequested = java.util.concurrent.atomic
        .AtomicBoolean(false)

    companion object {
        private const val DEDUPLICATION_WINDOW_MS = 500L
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()

        private const val PASS_THROUGH_COMMAND_BYTE = 0x54.toByte()
        private const val JOIN_TIMEOUT_MS = 5000L
        private const val INTERRUPT_JOIN_TIMEOUT_MS = 500L
    }

    /**
     * Starts the background listener thread.
     */
    fun start(readyLatch: CountDownLatch) {
        check(!closed.get()) { "Listener is already closed" }

        val arena = io.mazewall.ffi.memory.NativeArena
            .ofShared()
        val inputStream = NativeSocketInputStream(socketFd, arena, native.memory)

        val thread = Thread {
            try {
                runListenerLoop(inputStream, readyLatch)
            } catch (expectedWorkerFailure: Exception) {
                logger.log(java.util.logging.Level.SEVERE, "ProfilerTraceListener worker thread crashed", expectedWorkerFailure)
            } finally {
                if (closed.compareAndSet(false, true)) {
                    // Safe socket closure unifies cleanup across worker and main threads.
                    closeSocketOnce()
                }
                arena.close()
                workerTerminatedLatch.countDown()
            }
        }.apply {
            isDaemon = true
            name = "trace-listener-${socketFd.value}"
        }

        workerThread = thread
        thread.start()

        val collector = Thread {
            try {
                runBlocking {
                    for (event in eventChannel) {
                        accumulatedLogs.add(event)
                        val jvmFrames = event.jvmStackTrace
                        if (jvmFrames != null && stackTracesMap != null) {
                            stackTracesMap
                                .computeIfAbsent(event) {
                                CopyOnWriteArrayList<Array<StackTraceElement>>()
                            }.add(jvmFrames)
                        }
                        onEventCollected?.invoke(event)
                    }
                }
                collectionOutcome = CollectionOutcome.DRAINED
            } catch (expectedCollectorFailure: Exception) {
                collectionOutcome = CollectionOutcome.FAILED
                logger.log(java.util.logging.Level.SEVERE, "ProfilerTraceListener collector thread crashed", expectedCollectorFailure)
            } finally {
                if (collectionOutcome == CollectionOutcome.COLLECTING) collectionOutcome = CollectionOutcome.FAILED
                collectorTerminatedLatch.countDown()
            }
        }.apply {
            isDaemon = true
            name = "trace-collector-${socketFd.value}"
        }
        collectorThread = collector
        collector.start()
    }

    /**
     * Shuts down the listener using the graceful drain protocol:
     * 1. Sends PASS_THROUGH_COMMAND_BYTE to the daemon so it finishes writing any in-flight events.
     * 2. Waits for the listener thread to drain all remaining events until it sees EOF from the daemon.
     * 3. Only then closes the underlying socket FD.
     *
     * This prevents the race condition where the JVM closes the socket FD while the daemon is
     * still writing the last event (sendTraceEvent → write → EPIPE → event lost).
     */
    override fun close() {
        closeWithResult().throwIfIncomplete("Profiler trace listener cleanup failed")
    }

    @Synchronized
    fun closeWithResult(): SeccompCleanupOutcome {
        val requestDrain = closed.compareAndSet(false, true)
        if (!requestDrain && workerThread == null && collectorThread == null) {
            closeSocketOnce()
            return cleanupOutcome
        }

        logger.fine("Closing ProfilerTraceListener for fd=${socketFd.value}")
        if (requestDrain) gracefulDrainRequested.set(true)

        try {
            // Step 1: Signal the daemon to finish up. The daemon receives this byte,
            // enters pass-through mode for this session, and closes its side of the socket.
            // This triggers EOF on our read side without shutting down the global daemon.
            if (requestDrain) logCommandWrite(sendCommand(PASS_THROUGH_COMMAND_BYTE))

            var workerJoined = false
            var collectorJoined = false

            // Step 2: Join the reader after its terminal record and then the consuming collector.
            workerThread?.let { workerJoined = joinWorkerForDrain(it) }

            collectorThread?.let {
                try {
                    it.join(JOIN_TIMEOUT_MS)
                    if (it.isAlive) {
                        logger.warning("Trace collector thread for fd=${socketFd.value} did not terminate within $JOIN_TIMEOUT_MS ms")
                        it.interrupt()
                    } else {
                        collectorJoined = true
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            drainComplete = workerJoined &&
                collectorJoined &&
                collectionOutcome == CollectionOutcome.DRAINED &&
                droppedEvents == 0 &&
                state is TraceListenerState.Drained
            if (!collectorJoined) {
                droppedEvents += 1
            }
        } finally {
            // Step 3: Retire the descriptor once and retain unfinished thread owners.
            closeSocketOnce()
            retainUnfinishedThreads()
        }
        return cleanupOutcome
    }

    private fun retainUnfinishedThreads() {
        if (workerThread?.isAlive == false) workerThread = null
        if (collectorThread?.isAlive == false) collectorThread = null
        threadCleanupOutcome = if (workerThread != null || collectorThread != null) {
            SeccompCleanupOutcome.Incomplete(listOf(IllegalStateException("Profiler reader or collector is still physically alive")), emptyList())
        } else {
            SeccompCleanupOutcome.Complete
        }
    }

    /**
     * Instructs the daemon to enter Pass-Through mode. The daemon will stop sending
     * events to this socket and simply loop executing `SECCOMP_USER_NOTIF_FLAG_CONTINUE`
     * for all notifications until the tracee process fully exits.
     * The daemon will close its end of the socket, which sends EOF to our listener thread.
     */
    fun passThrough() = close()

    private fun joinWorkerForDrain(worker: Thread): Boolean =
        try {
            worker.join(JOIN_TIMEOUT_MS)
            if (worker.isAlive) {
                logger.warning("Trace listener thread for fd=${socketFd.value} did not terminate within $JOIN_TIMEOUT_MS ms")
                worker.interrupt()
                worker.join(INTERRUPT_JOIN_TIMEOUT_MS)
                false
            } else {
                true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun sendCommand(commandByte: Byte): TraceCommandWriteResult {
        try {
            val res = ProfilerNativeCommandWriter.writeByte(native.memory, socketFd, commandByte)
            return TraceCommandWriteResult.from(res)
        } catch (expectedCommandFailure: Exception) {
            return TraceCommandWriteResult.Threw(expectedCommandFailure)
        }
    }

    private fun logCommandWrite(result: TraceCommandWriteResult) {
        when (result) {
            TraceCommandWriteResult.Written -> Unit
            is TraceCommandWriteResult.NativeFailure -> logger.fine("Failed to send pass-through command: errno=${result.errno}")
            is TraceCommandWriteResult.ShortWrite -> logger.fine("Failed to send pass-through command: bytes=${result.bytes}")
            is TraceCommandWriteResult.Threw -> logger.fine("Failed to send pass-through command: ${result.cause.javaClass.simpleName}")
        }
    }

    private fun runListenerLoop(
        inputStream: InputStream,
        readyLatch: CountDownLatch,
    ) {
        val dis = DataInputStream(BufferedInputStream(inputStream))
        try {
            try {
                // Read handshake ACK from the daemon confirming the listener FD was received.
                val handshakeAck = dis.readByte()
                if (handshakeAck != PROTOCOL_ACK_BYTE) {
                    advanceListener(TraceListenerEvent.HandshakeRejected)
                    logger.warning("Invalid handshake ACK from daemon: $handshakeAck; rejecting trace stream")
                    return
                }
                advanceListener(TraceListenerEvent.HandshakeReceived)
                // No config bytes are sent; event delivery uses the fixed per-frame ACK protocol.
            } finally {
                readyLatch.countDown()
            }

            while (true) {
                advanceListener(TraceListenerEvent.AwaitEvent)
                val decoded = readEventOrStop(dis) ?: break

                advanceListener(TraceListenerEvent.EventRead(decoded.event))
                processEvent(decoded.event, decoded.diagnosticRecord)
            }
        } catch (e: java.io.IOException) {
            logger.log(java.util.logging.Level.WARNING, "Trace listener error", e)
        } finally {
            state = TraceListenerMachine.evaluate(state, TraceListenerEvent.SocketClosed)
            eventQueue.close()
        }
    }

    private fun readEventOrStop(dis: DataInputStream): DecodedTraceEvent? =
        try {
            readNextEvent(dis)
        } catch (_: java.io.EOFException) {
            null
        } catch (expectedCloseIoFailure: java.io.IOException) {
            if (closed.get() && state !is TraceListenerState.ProtocolViolation) {
                logger.log(java.util.logging.Level.FINE, "Trace listener loop interrupted by close", expectedCloseIoFailure)
                null
            } else {
                throw expectedCloseIoFailure
            }
        }

    private fun readNextEvent(dis: DataInputStream): DecodedTraceEvent? {
        val tidValue = dis.readInt()
        if (tidValue == TraceStreamEndWire.MARKER) {
            readTerminal(dis)
            return null
        }
        advanceListener(TraceListenerEvent.HeaderRead(tidValue))
        val decoded = TraceEventDecoder.decodeBody(dis, tidValue)
        if (decoded is TraceEventDecodeResult.ProtocolViolation) {
            // A complete TID header identifies an outstanding notification. Settle it before
            // rejecting the frame, just as processEvent settles accepted events in its finally.
            droppedEvents += 1
            sendAck()
            throw java.io.IOException(decoded.reason)
        }
        decoded as TraceEventDecodeResult.Event
        val diagnosticRecord = BenchmarkRawSyscallDiagnostics.record(tidValue, decoded.event.syscallName, decoded.rawArguments)
        advanceListener(TraceListenerEvent.SyscallRead(tidValue, decoded.syscallNameLength))
        advanceListener(TraceListenerEvent.ArgumentsRead(tidValue, decoded.event.syscallName, decoded.argumentCount))
        // NOTE: This protocol is strictly synchronous. The daemon suspends the tracee thread
        // in kernel space using seccomp and awaits an ACK from this listener thread. While the
        // tracee thread is suspended in the kernel, we can safely and reliably capture its JVM
        // stack trace (via captureStackTrace) without the thread moving past the active syscall frame.
        // Once the stack trace is captured, we send the ACK to unblock the tracee.
        return DecodedTraceEvent(decoded.event, diagnosticRecord)
    }

    private fun readTerminal(dis: DataInputStream) {
        val reason = try {
            TraceStreamEndWire.read(dis)
        } catch (_: java.io.IOException) {
            advanceListener(TraceListenerEvent.ProtocolRejected("Truncated trace terminal record"))
            return
        } catch (_: IllegalArgumentException) {
            advanceListener(TraceListenerEvent.ProtocolRejected("Invalid trace terminal record"))
            return
        }
        advanceListener(TraceListenerEvent.DrainConfirmed(reason, gracefulDrainRequested.get()))
    }

    private fun advanceListener(event: TraceListenerEvent) {
        val next = TraceListenerMachine.evaluate(state, event)
        state = next
        if (next is TraceListenerState.ProtocolViolation) {
            // Retirement may already be draining. Preserve lost evidence before propagating so
            // close cannot mistake joined threads for a complete event stream.
            droppedEvents += 1
            drainComplete = false
            throw TraceListenerProtocolException(next.reason)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Preserve fatal identity while settling the independent ACK obligation.
    private fun processEvent(
        event: TraceEvent,
        diagnosticRecord: BenchmarkRawSyscallDiagnostics.Record?,
    ) {
         var primary: Throwable? = null
         try {
             processAndQueueEvent(event, diagnosticRecord)
        } catch (failure: Throwable) {
            primary = failure
        }
        try {
            sendAck()
            BenchmarkRawSyscallDiagnostics.activeCapture()?.listenerAcknowledged(diagnosticRecord)
        } catch (ackFailure: Throwable) {
            val processingFailure = primary
            if (processingFailure == null) throw ackFailure
            if (processingFailure !== ackFailure) processingFailure.addSuppressed(ackFailure)
        }
        primary?.let { throw it }
    }

    private fun processAndQueueEvent(
        event: TraceEvent,
        diagnosticRecord: BenchmarkRawSyscallDiagnostics.Record?,
    ) {
         val resolution = event.pathResolution
         if (
             BenchmarkRawSyscallDiagnostics.activeCapture() == null &&
             resolution is PathResolution.Resolved &&
             isDuplicate(event.syscallName, resolution)
         ) {
             return
         }

         val stackStarted = System.nanoTime()
         event.jvmStackTrace = captureStackTrace(event)
         val stackCaptureNanos = System.nanoTime() - stackStarted
         val queueStarted = System.nanoTime()
         eventQueue.offer(event)
         BenchmarkRawSyscallDiagnostics.activeCapture()?.listenerProcessed(
             diagnosticRecord,
             stackCaptureNanos,
             System.nanoTime() - queueStarted,
         )
    }

    private fun sendAck() {
        val result = sendCommand(PROTOCOL_ACK_BYTE)
        val failure = when (result) {
            TraceCommandWriteResult.Written -> return
            is TraceCommandWriteResult.NativeFailure -> java.io.IOException("Profiler ACK failed: errno=${result.errno}")
            is TraceCommandWriteResult.ShortWrite -> java.io.IOException("Profiler ACK incomplete: bytes=${result.bytes}")
            is TraceCommandWriteResult.Threw -> java.io.IOException("Profiler ACK write threw", result.cause)
        }
        ackWriteFailure = result
        droppedEvents += 1
        drainComplete = false
        // The daemon is still waiting for this ACK. Closing immediately exposes failure even if
        // another complete frame is buffered locally; no subsequent frame may be processed.
        closeSocketOnce()
        val cleanup = cleanupOutcome
        if (cleanup is SeccompCleanupOutcome.Incomplete) cleanup.failures.forEach(failure::addSuppressed)
        throw failure
    }

    private fun isDuplicate(
        syscallName: String,
        resolution: PathResolution.Resolved,
    ): Boolean {
        // Keep the compatibility string cache, but encode typed paths in operand order with
        // lengths: delimiters inside a path cannot alias another operand tuple.
        val cacheKey = buildString {
            append("resolved:").append(syscallName.length).append(':').append(syscallName)
            for (path in resolution.paths) append(':').append(path.length).append(':').append(path)
        }
        val now = System.currentTimeMillis()
        val lastSeen = pathCache[cacheKey] ?: 0L
        return if (now - lastSeen < DEDUPLICATION_WINDOW_MS) {
            logger.fine("Deduplicated profiler event")
            true
        } else {
            pathCache[cacheKey] = now
            false
        }
    }

    private fun captureStackTrace(event: TraceEvent): Array<StackTraceElement>? {
        if (stackTracesMap == null || event.tid.value == 0) return null
        val threadToProfile = threadFor(event.tid)
        return if (threadToProfile != null) {
            threadToProfile.stackTrace
        } else {
            arrayOf(StackTraceElement("<untracked_descendant_thread>", "run", null, -1))
        }
    }
}

private data class DecodedTraceEvent(
    val event: TraceEvent,
    val diagnosticRecord: BenchmarkRawSyscallDiagnostics.Record?,
)

internal sealed interface TraceCommandWriteResult {
    data object Written : TraceCommandWriteResult

    data class NativeFailure(
        val errno: Int,
    ) : TraceCommandWriteResult

    data class Threw(
        val cause: Exception,
    ) : TraceCommandWriteResult

    data class ShortWrite(
        val bytes: Long,
    ) : TraceCommandWriteResult

    companion object {
        fun from(result: LinuxNative.SyscallResult<Long, *>): TraceCommandWriteResult =
            when (result) {
                is LinuxNative.SyscallResult.Success -> if (result.value == 1L) Written else ShortWrite(result.value)
                is LinuxNative.SyscallResult.Error -> NativeFailure(result.errno)
            }
    }
}
