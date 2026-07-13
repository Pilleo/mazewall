package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.nativeScope
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.profiler.Profiler
import io.mazewall.profiler.engine.TraceEvent
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.lang.foreign.Arena
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
    private var workerThread: Thread? = null

    var state: TraceListenerState = TraceListenerState.Disconnected
        private set

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

        val arena = Arena.ofShared()
        val inputStream = NativeSocketInputStream(socketFd, arena)

        val thread = Thread {
            try {
                runListenerLoop(inputStream, readyLatch)
            } catch (t: Throwable) {
                logger.log(java.util.logging.Level.SEVERE, "ProfilerTraceListener worker thread crashed with fatal error", t)
                if (closed.compareAndSet(false, true)) {
                    try {
                        socketFd.close()
                    } catch (ignored: Exception) {}
                }
            } finally {
                arena.close()
                inputStream.close()
            }
        }.apply {
            isDaemon = true
            name = "trace-listener-${socketFd.value}"
        }

        workerThread = thread
        thread.start()
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

        // Step 1: Signal the daemon to finish up. The daemon receives this byte in
        // handleShutdownRequest(), terminates its session loop, and then closes its
        // side of the socket. This triggers EOF on our read side.
        sendShutdownCommand()

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

        // Step 3: Close the socket FD only after draining.
        socketFd.close()
        workerThread = null
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
        
        socketFd.close()
        workerThread = null
    }

    private fun sendCommand(commandByte: Byte) {
        // Best-effort: if the socket is already closed or the daemon is dead, ignore errors.
        // Use a confined native arena — MemorySegment.ofArray() creates a heap segment that
        // cannot be passed to native write() syscalls via the FFM API.
        try {
            nativeScope { arena ->
                val buf = arena.allocate(1)
                buf.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, commandByte)
                LinuxNative.withTransaction { LinuxNative.memory.write(socketFd, buf, 1) }
            }
        } catch (ignored: Exception) {}
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
                    break // Graceful shutdown or socket closed
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
        // stack trace (via accumulateStackTrace) without the thread moving past the active syscall frame.
        // Once the stack trace is captured, we send the ACK to unblock the tracee.
        return TraceEvent(tidValue = tidValue, syscallName = syscallName, args = args, paths = paths, stackTrace = null)
    }

    private fun processEvent(event: TraceEvent) {
         try {
             System.err.println("[TRACE-LISTENER-DEBUG] processEvent: tid=${event.tid.value}, syscall=${event.syscallName}")
             if (event.paths.isNotEmpty() && isDuplicate(event)) {
                 System.err.println("[TRACE-LISTENER-DEBUG] duplicate event, skipping")
                 return
             }

             accumulatedLogs.add(event)
             accumulateStackTrace(event)
         } finally {
             sendAck()
         }
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

    private fun accumulateStackTrace(event: TraceEvent) {
        if (stackTracesMap == null || event.tid.value == 0) return
        val threadToProfile = Profiler.threadRegistry[event.tid]
        val frames = if (threadToProfile != null) {
            threadToProfile.stackTrace
        } else {
            arrayOf(StackTraceElement("<untracked_descendant_thread>", "run", null, -1))
        }
        stackTracesMap
            .computeIfAbsent(event) {
                CopyOnWriteArrayList<Array<StackTraceElement>>()
            }.add(frames)
    }
}
