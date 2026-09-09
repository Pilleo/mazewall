package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.profiler.engine.PathResolution
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.ffi.ProfilerNativeCommandWriter
import io.mazewall.profiler.throwIfIncomplete
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
    var cleanupOutcome: SeccompCleanupOutcome = SeccompCleanupOutcome.Complete
        private set
    private var workerThread: Thread? = null
    private var collectorThread: Thread? = null

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
                cleanupOutcome = when (result) {
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
        cleanupOutcome = SeccompCleanupOutcome.Incomplete(listOf(failure), retryableOwnership())
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

    /** True only after EOF that follows a locally initiated pass-through/shutdown. */
    @Volatile
    var drainComplete: Boolean = false
        private set

    @Volatile
    var droppedEvents: Int = 0
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
                inputStream.close()
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
            } catch (expectedCollectorFailure: Exception) {
                logger.log(java.util.logging.Level.SEVERE, "ProfilerTraceListener collector thread crashed", expectedCollectorFailure)
            } finally {
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

            // Step 2: Wait for the listener thread to drain remaining events and see EOF.
            // The thread exits via EOFException once the daemon closes its socket end.
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

            drainComplete = gracefulDrainRequested.get() && workerJoined && collectorJoined && droppedEvents == 0
            if (!collectorJoined) {
                droppedEvents += 1
            }
        } finally {
            // Step 3: Close the socket FD only after draining in an idempotent way.
            closeSocketOnce()
            workerThread = null
            collectorThread = null
        }
        return cleanupOutcome
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
            is TraceCommandWriteResult.Threw -> logger.fine("Failed to send pass-through command: ${result.cause.message}")
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
                    state = TraceListenerMachine.evaluate(state, TraceListenerEvent.HandshakeRejected)
                    logger.warning("Invalid handshake ACK from daemon: $handshakeAck; rejecting trace stream")
                    return
                }
                state = TraceListenerMachine.evaluate(state, TraceListenerEvent.HandshakeReceived)
                // No config bytes are sent. The daemon uses fire-and-forget delivery;
                // no bypassedTids or captureStackTraces negotiation is required.
            } finally {
                readyLatch.countDown()
            }

            System.err.println("[TRACE-LISTENER-DEBUG] Loop started, ready to read events")
            while (true) {
                state = TraceListenerMachine.evaluate(state, TraceListenerEvent.AwaitEvent)
                val event = try {
                    readNextEvent(dis)
                } catch (_: java.io.EOFException) {
                    System.err.println("[TRACE-LISTENER-DEBUG] EOFException, closing loop")
                    break
                } catch (expectedCloseIoFailure: java.io.IOException) {
                    if (closed.get()) {
                        logger.log(
                            java.util.logging.Level.FINE,
                            "Trace listener loop interrupted by close",
                            expectedCloseIoFailure,
                        )
                        break
                    }
                    throw expectedCloseIoFailure
                }

                state = TraceListenerMachine.evaluate(state, TraceListenerEvent.EventRead(event))
                processEvent(event)
            }
        } catch (e: java.io.IOException) {
            logger.log(java.util.logging.Level.WARNING, "Trace listener error", e)
        } finally {
            state = TraceListenerMachine.evaluate(state, TraceListenerEvent.SocketClosed)
            eventQueue.close()
        }
    }

    private fun readNextEvent(dis: DataInputStream): TraceEvent {
        System.err.println("[TRACE-LISTENER-DEBUG] Awaiting/reading next event...")
        val tidValue = dis.readInt()
        state = TraceListenerMachine.evaluate(state, TraceListenerEvent.HeaderRead(tidValue))
        val decoded = TraceEventDecoder.decodeBody(dis, tidValue)
        if (decoded is TraceEventDecodeResult.ProtocolViolation) {
            // A complete TID header identifies an outstanding notification. Settle it before
            // rejecting the frame, just as processEvent settles accepted events in its finally.
            droppedEvents += 1
            sendAck()
            throw java.io.IOException(decoded.reason)
        }
        decoded as TraceEventDecodeResult.Event
        state = TraceListenerMachine.evaluate(
            state,
            TraceListenerEvent.SyscallRead(tidValue, decoded.syscallNameLength),
        )
        state = TraceListenerMachine.evaluate(
            state,
            TraceListenerEvent.ArgumentsRead(tidValue, decoded.event.syscallName, decoded.argumentCount),
        )
        System.err.println(
            "[TRACE-LISTENER-DEBUG] Read event header: tid=$tidValue, " +
                "syscall=${decoded.event.syscallName}, paths=${decoded.event.paths}",
        )
        // NOTE: This protocol is strictly synchronous. The daemon suspends the tracee thread
        // in kernel space using seccomp and awaits an ACK from this listener thread. While the
        // tracee thread is suspended in the kernel, we can safely and reliably capture its JVM
        // stack trace (via captureStackTrace) without the thread moving past the active syscall frame.
        // Once the stack trace is captured, we send the ACK to unblock the tracee.
        return decoded.event
    }

    private fun processEvent(event: TraceEvent) {
         try {
             processAndQueueEvent(event)
         } finally {
             sendAck()
         }
    }

    private fun processAndQueueEvent(event: TraceEvent) {
         System.err.println("[TRACE-LISTENER-DEBUG] processEvent: tid=${event.tid.value}, syscall=${event.syscallName}")
         val resolution = event.pathResolution
         if (resolution is PathResolution.Resolved && isDuplicate(event.syscallName, resolution)) {
             System.err.println("[TRACE-LISTENER-DEBUG] duplicate event, skipping")
             return
         }

         event.jvmStackTrace = captureStackTrace(event)
         eventQueue.offer(event)
    }

    private fun sendAck() {
        sendCommand(PROTOCOL_ACK_BYTE)
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
            logger.fine("[PROFILER] Deduplicated duplicate event for $cacheKey")
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

internal sealed interface TraceCommandWriteResult {
    data object Written : TraceCommandWriteResult

    data class NativeFailure(
        val errno: Int,
    ) : TraceCommandWriteResult

    data class Threw(
        val cause: Exception,
    ) : TraceCommandWriteResult

    companion object {
        fun from(result: LinuxNative.SyscallResult<Long, *>): TraceCommandWriteResult =
            when (result) {
                is LinuxNative.SyscallResult.Success -> Written
                is LinuxNative.SyscallResult.Error -> NativeFailure(result.errno)
            }
    }
}
