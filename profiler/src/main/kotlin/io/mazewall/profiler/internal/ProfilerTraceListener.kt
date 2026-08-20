package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.close
import io.mazewall.core.Tid
import io.mazewall.profiler.Profiler
import io.mazewall.profiler.engine.TraceEvent
import java.io.BufferedInputStream
import java.io.DataInputStream
import io.mazewall.ffi.memory.ConfinedSegment
import java.io.InputStream
import java.lang.foreign.Arena
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
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
@Suppress("SwallowedException")
internal class ProfilerTraceListener(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    private val accumulatedLogs: MutableList<TraceEvent>,
    private val stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
    private val pathCache: MutableMap<String, Long>,
) : AutoCloseable {
    private val logger = Logger.getLogger(ProfilerTraceListener::class.java.name)
    private val closed = AtomicBoolean(false)
    // Thread-safe idempotent close guard to prevent native double-close.
    private val socketClosed = AtomicBoolean(false)
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
    private fun closeSocketOnce() {
        if (socketClosed.compareAndSet(false, true)) {
            try {
                logger.fine("Executing idempotent socketFd.close() for fd=${socketFd.value}")
                socketFd.close()
            } catch (ignored: Exception) {
                // Ignore any close exceptions
            }
        }
    }

    internal val eventQueue = TraceEventQueue()
    val eventChannel = eventQueue.channel
    val eventFlow: Flow<TraceEvent> = eventChannel.receiveAsFlow()

    var state: TraceListenerState = TraceListenerState.Disconnected
        private set

    /** True only after EOF that follows a locally initiated pass-through/shutdown. */
    @Volatile
    var drainComplete: Boolean = false
        private set

    private val gracefulDrainRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        private const val DEDUPLICATION_WINDOW_MS = 500L
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
        // Signals the daemon to finish writing any in-flight events and close its socket end.
        // On receipt the daemon session loop terminates gracefully (LoopAction.Shutdown), which
        // allows the JVM listener to drain the remaining events before seeing EOF.
        private const val SHUTDOWN_COMMAND_BYTE = 0x53.toByte()
        private const val PASS_THROUGH_COMMAND_BYTE = 0x54.toByte()
        private const val JOIN_TIMEOUT_MS = 5000L
        private const val INTERRUPT_JOIN_TIMEOUT_MS = 500L
    }

    /**
     * Starts the background listener thread.
     */
    fun start(readyLatch: CountDownLatch) {
        if (closed.get()) throw IllegalStateException("Listener is already closed")

        val arena = io.mazewall.ffi.memory.NativeArena.ofShared()
        val inputStream = NativeSocketInputStream(socketFd, arena)

        val thread = Thread {
            try {
                runListenerLoop(inputStream, readyLatch)
            } catch (t: Throwable) {
                logger.log(java.util.logging.Level.SEVERE, "ProfilerTraceListener worker thread crashed with fatal error", t)
            } finally {
                if (closed.compareAndSet(false, true)) {
                    // Safe socket closure unifies cleanup across worker and main threads.
                    closeSocketOnce()
                }
                arena.close()
                inputStream.close()
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
                            stackTracesMap.computeIfAbsent(event) {
                                CopyOnWriteArrayList<Array<StackTraceElement>>()
                            }.add(jvmFrames)
                        }
                    }
                }
            } catch (t: Throwable) {
                logger.log(java.util.logging.Level.SEVERE, "ProfilerTraceListener collector thread crashed with fatal error", t)
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
     * 1. Sends SHUTDOWN_COMMAND_BYTE to the daemon so it finishes writing any in-flight events.
     * 2. Waits for the listener thread to drain all remaining events until it sees EOF from the daemon.
     * 3. Only then closes the underlying socket FD.
     *
     * This prevents the race condition where the JVM closes the socket FD while the daemon is
     * still writing the last event (sendTraceEvent → write → EPIPE → event lost).
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        logger.fine("Closing ProfilerTraceListener for fd=${socketFd.value}")
        gracefulDrainRequested.set(true)

        try {
            // Step 1: Signal the daemon to finish up. The daemon receives this byte,
            // enters pass-through mode for this session, and closes its side of the socket.
            // This triggers EOF on our read side without shutting down the global daemon.
            try {
                sendCommand(PASS_THROUGH_COMMAND_BYTE)
            } catch (e: Exception) {
                logger.fine("Failed to send PASS_THROUGH_COMMAND_BYTE: ${e.message}")
            }

            // Step 2: Wait for the listener thread to drain remaining events and see EOF.
            // The thread exits via EOFException once the daemon closes its socket end.
            workerThread?.let {
                try {
                    it.join(JOIN_TIMEOUT_MS)
                    if (it.isAlive) {
                        logger.warning("Trace listener thread for fd=${socketFd.value} did not terminate within $JOIN_TIMEOUT_MS ms")
                        it.interrupt()
                        it.join(INTERRUPT_JOIN_TIMEOUT_MS)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            collectorThread?.let {
                try {
                    it.join(JOIN_TIMEOUT_MS)
                    if (it.isAlive) {
                        logger.warning("Trace collector thread for fd=${socketFd.value} did not terminate within $JOIN_TIMEOUT_MS ms")
                        it.interrupt()
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } finally {
            // Step 3: Close the socket FD only after draining in an idempotent way.
            closeSocketOnce()
            workerThread = null
            collectorThread = null
        }
    }

    private fun sendShutdownCommand() {
        sendCommand(SHUTDOWN_COMMAND_BYTE)
    }

    /**
     * Instructs the daemon to enter Pass-Through mode. The daemon will stop sending
     * events to this socket and simply loop executing `SECCOMP_USER_NOTIF_FLAG_CONTINUE`
     * for all notifications until the tracee process fully exits.
     * The daemon will close its end of the socket, which sends EOF to our listener thread.
     */
    fun passThrough() {
        if (!closed.compareAndSet(false, true)) return

        logger.fine("Entering Pass-Through mode for ProfilerTraceListener fd=${socketFd.value}")
        gracefulDrainRequested.set(true)

        try {
            sendCommand(PASS_THROUGH_COMMAND_BYTE)

            workerThread?.let {
                try {
                    it.join(JOIN_TIMEOUT_MS)
                    if (it.isAlive) {
                        logger.warning("Trace listener thread for fd=${socketFd.value} did not terminate within $JOIN_TIMEOUT_MS ms during passThrough")
                        it.interrupt()
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            collectorThread?.let {
                try {
                    it.join(JOIN_TIMEOUT_MS)
                    if (it.isAlive) {
                        logger.warning("Trace collector thread for fd=${socketFd.value} did not terminate within $JOIN_TIMEOUT_MS ms during passThrough")
                        it.interrupt()
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } finally {
            // Gracefully close the socket once using the idempotent close guard.
            closeSocketOnce()
            workerThread = null
            collectorThread = null
        }
    }

    private fun sendCommand(commandByte: Byte) {
        try {
            Arena.ofConfined().use { arena ->
                val buf = arena.allocate(1)
                buf.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, commandByte)
                val res = LinuxNative.memory.write(socketFd, ConfinedSegment(buf), 1)
                if (res is LinuxNative.SyscallResult.Error) {
                    System.err.println("[TRACE-LISTENER-DEBUG] sendCommand write failed with errno: ${res.errno}")
                } else {
                    System.err.println("[TRACE-LISTENER-DEBUG] sendCommand write succeeded")
                }
            }
        } catch (e: Exception) {
            System.err.println("[TRACE-LISTENER-DEBUG] sendCommand threw exception: ${e.message}")
        }
    }

    @Suppress("MagicNumber")
    private fun runListenerLoop(
        inputStream: InputStream,
        readyLatch: CountDownLatch,
    ) {
        val dis = DataInputStream(BufferedInputStream(inputStream))
        try {
            try {
                // Read handshake ACK from the daemon confirming the listener FD was received.
                state = TraceListenerState.Disconnected
                val handshakeAck = dis.readByte()
                if (handshakeAck != PROTOCOL_ACK_BYTE) {
                    logger.warning("Invalid handshake ACK from daemon: $handshakeAck")
                }
                // No config bytes are sent. The daemon uses fire-and-forget delivery;
                // no bypassedTids or captureStackTraces negotiation is required.
            } finally {
                readyLatch.countDown()
            }

            System.err.println("[TRACE-LISTENER-DEBUG] Loop started, ready to read events")
            while (!closed.get()) {
                state = TraceListenerState.AwaitingEvent
                val event = try {
                    readNextEvent(dis)
                } catch (e: java.io.EOFException) {
                    System.err.println("[TRACE-LISTENER-DEBUG] EOFException, closing loop")
                    if (gracefulDrainRequested.get()) {
                        drainComplete = true
                    }
                    break
                } catch (e: java.io.IOException) {
                    if (closed.get()) {
                        logger.log(java.util.logging.Level.FINE, "Trace listener loop interrupted by close", e)
                        break
                    }
                    throw e
                }

                state = TraceListenerState.ProcessingEvent(event)
                processEvent(event)
            }
        } catch (e: java.io.IOException) {
            logger.log(java.util.logging.Level.WARNING, "Trace listener error", e)
        } finally {
            state = TraceListenerState.Disconnected
            eventQueue.close()
        }
    }

    private fun readNextEvent(dis: DataInputStream): TraceEvent {
        System.err.println("[TRACE-LISTENER-DEBUG] Awaiting/reading next event...")
        val tidValue = dis.readInt()
        state = TraceListenerState.ReadingHeader(tidValue)

        val syscallNameLen = dis.readInt()
        state = TraceListenerState.ReadingSyscall(tidValue, syscallNameLen)

        val syscallNameBytes = ByteArray(syscallNameLen)
        dis.readFully(syscallNameBytes)
        val syscallName = String(syscallNameBytes, Charsets.UTF_8)

        val argsCount = dis.readInt()
        state = TraceListenerState.ReadingArguments(tidValue, syscallName, argsCount)

        val args = LongArray(argsCount)
        for (i in 0 until argsCount) {
            args[i] = dis.readLong()
        }

        val pathsCount = dis.readInt()
        val paths = mutableListOf<String>()
        for (i in 0 until pathsCount) {
            val pathLen = dis.readInt()
            val pathBytes = ByteArray(pathLen)
            dis.readFully(pathBytes)
            paths.add(String(pathBytes, Charsets.UTF_8))
        }

        System.err.println("[TRACE-LISTENER-DEBUG] Read event header: tid=$tidValue, syscall=$syscallName, paths=$paths")
        // NOTE: This protocol is strictly synchronous. The daemon suspends the tracee thread
        // in kernel space using seccomp and awaits an ACK from this listener thread. While the
        // tracee thread is suspended in the kernel, we can safely and reliably capture its JVM
        // stack trace (via captureStackTrace) without the thread moving past the active syscall frame.
        // Once the stack trace is captured, we send the ACK to unblock the tracee.
        return TraceEvent(tidValue = tidValue, syscallName = syscallName, args = args, paths = paths, stackTrace = null)
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
         if (event.paths.isNotEmpty() && isDuplicate(event)) {
             System.err.println("[TRACE-LISTENER-DEBUG] duplicate event, skipping")
             return
         }

         event.jvmStackTrace = captureStackTrace(event)
         eventQueue.offer(event)
    }

    private fun sendAck() {
        sendCommand(PROTOCOL_ACK_BYTE)
    }

    private fun isDuplicate(event: TraceEvent): Boolean {
        val cacheKey = "${event.syscallName}:${event.paths.sorted().joinToString(",")}"
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
        val threadToProfile = Profiler.threadRegistry[event.tid]
        return if (threadToProfile != null) {
            threadToProfile.stackTrace
        } else {
            arrayOf(StackTraceElement("<untracked_descendant_thread>", "run", null, -1))
        }
    }
}
