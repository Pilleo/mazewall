package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.profiler.Profiler
import io.mazewall.profiler.engine.SyscallEvent
import io.mazewall.profiler.engine.SyscallEventState
import io.mazewall.profiler.engine.TraceEvent
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Background listener that reads [SyscallEvent]s from a tracee socket and resolves them.
 *
 * Implements [AutoCloseable] to ensure that the worker thread is deterministically joined
 * and resources (Arena, Socket) are released upon shutdown.
 */
@Suppress("SwallowedException")
internal class ProfilerTraceListener(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    private val accumulatedLogs: MutableList<SyscallEvent<SyscallEventState.Resolved>>,
    private val stackTracesMap: MutableMap<SyscallEvent<SyscallEventState.Resolved>, MutableList<Array<StackTraceElement>>>?,
    private val pathCache: MutableMap<String, Long>,
    private val workerThreadProvider: () -> Thread?,
) : AutoCloseable {
    private val logger = Logger.getLogger(ProfilerTraceListener::class.java.name)
    private val closed = AtomicBoolean(false)
    private var workerThread: Thread? = null

    var state: TraceListenerState = TraceListenerState.Disconnected
        private set

    companion object {
        private const val DEDUPLICATION_WINDOW_MS = 500L
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
        private const val JOIN_TIMEOUT_MS = 1000L
    }

    /**
     * Starts the background listener thread.
     */
    fun start() {
        if (closed.get()) throw IllegalStateException("Listener is already closed")

        val arena = Arena.ofShared()
        val inputStream = NativeSocketInputStream(socketFd, arena)

        val thread = Thread {
            try {
                runListenerLoop(inputStream, arena)
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
     * Shuts down the listener, closes the socket, and waits for the worker thread to finish.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        logger.fine("Closing ProfilerTraceListener for fd=${socketFd.value}")

        // Closing the socket will cause the NativeSocketInputStream.read() to unblock/fail
        // allowing the worker loop to terminate gracefully.
        socketFd.close()

        workerThread?.let {
            try {
                it.join(JOIN_TIMEOUT_MS)
                if (it.isAlive) {
                    logger.warning("Trace listener thread for fd=${socketFd.value} did not terminate within $JOIN_TIMEOUT_MS ms")
                    it.interrupt()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        workerThread = null
    }

    private fun runListenerLoop(
        inputStream: InputStream,
        arena: Arena,
    ) {
        val ackBuf = arena.allocate(1)
        ackBuf.set(ValueLayout.JAVA_BYTE, 0L, PROTOCOL_ACK_BYTE)

        val dis = DataInputStream(BufferedInputStream(inputStream))
        try {
            while (!closed.get()) {
                state = TraceListenerState.AwaitingEvent
                val legacyEvent = try {
                    readNextEvent(dis)
                } catch (e: java.io.EOFException) {
                    break // Graceful shutdown or socket closed
                } catch (e: java.io.IOException) {
                    if (closed.get()) {
                        logger.log(java.util.logging.Level.FINE, "Trace listener loop interrupted by close", e)
                        break
                    }
                    throw e
                }

                val event = SyscallEvent.fromTraceEvent(legacyEvent)
                state = TraceListenerState.ProcessingEvent(legacyEvent)
                processEvent(event, ackBuf)
            }
        } catch (e: java.io.IOException) {
            logger.log(java.util.logging.Level.WARNING, "Trace listener error", e)
        } finally {
            state = TraceListenerState.Disconnected
        }
    }

    private fun readNextEvent(dis: DataInputStream): TraceEvent {
        val pid = dis.readInt()
        state = TraceListenerState.ReadingHeader(pid)

        val syscallNameLen = dis.readInt()
        state = TraceListenerState.ReadingSyscall(pid, syscallNameLen)

        val syscallNameBytes = ByteArray(syscallNameLen)
        dis.readFully(syscallNameBytes)
        val syscallName = String(syscallNameBytes, Charsets.UTF_8)

        val argsCount = dis.readInt()
        state = TraceListenerState.ReadingArguments(pid, syscallName, argsCount)

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

        val threadToProfile = Profiler.threadRegistry[io.mazewall.core.Pid(pid)] ?: workerThreadProvider()
        val stackTrace = threadToProfile?.stackTrace?.map { it.toString() }
        return TraceEvent(pid = pid, syscallName = syscallName, args = args, paths = paths, stackTrace = stackTrace)
    }

    private fun processEvent(
        event: SyscallEvent<SyscallEventState.Resolved>,
        ackBuf: MemorySegment,
    ) {
        if (event.paths.isNotEmpty() && isDuplicate(event)) {
            sendAckIfNecessary(event.pid, ackBuf)
            return
        }

        accumulateStackTrace(event)
        accumulatedLogs.add(event)
        sendAckIfNecessary(event.pid, ackBuf)
    }

    private fun isDuplicate(event: SyscallEvent<SyscallEventState.Resolved>): Boolean {
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

    private fun accumulateStackTrace(event: SyscallEvent<SyscallEventState.Resolved>) {
        if (stackTracesMap == null) return
        val threadToProfile = Profiler.threadRegistry[event.pid] ?: workerThreadProvider()
        if (threadToProfile != null) {
            val frames = threadToProfile.stackTrace
            stackTracesMap
                .computeIfAbsent(event) {
                    CopyOnWriteArrayList<Array<StackTraceElement>>()
                }.add(frames)
        }
    }

    private fun sendAckIfNecessary(
        pid: io.mazewall.core.Pid,
        ackBuf: MemorySegment,
    ) {
        if (pid.value != 0) {
            LinuxNative.withTransaction { LinuxNative.memory.write(socketFd, ackBuf, 1) }
        }
    }
}
