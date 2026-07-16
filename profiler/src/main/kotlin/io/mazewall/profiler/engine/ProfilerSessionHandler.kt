package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.Tid
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.FdState
import io.mazewall.core.LoopAction
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import io.mazewall.recover
import io.mazewall.map
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reactor-style handler for a single Profiler session.
 * Manages the state machine transitions and event delivery protocol.
 */
internal class ProfilerSessionHandler(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    private val publisher: TraceEventPublisher,
    private val responder: SeccompResponder,
    private val ioOps: NativeIoOperations,
    private val memoryReader: ProfilerMemoryReader,
    private val syscallMap: Map<Int, String>,
    private val onShutdown: (String) -> Unit,
) {
    private val ledger = SessionEventLedger()
    private var state: ProfilerState = ProfilerState.ActiveSession(socketFd, listenerFd)

    private companion object {
        private const val NOTIF_ID_OFF = 0L
        private const val NOTIF_PID_OFF = 8L
        private const val NOTIF_NR_OFF = 16L
        private const val NOTIF_ARGS_OFF = 32L

        private val POLLFD_REVENTS_OFF = Layouts.POLLFD_REVENTS_OFFSET
        private const val POLLFD_STRUCT_SIZE = 8L

        private const val SHUTDOWN_COMMAND_BYTE = 0x53.toByte() // 'S'

        private const val MAX_SYSCALL_ARGS = 6

        private val SECCOMP_IOCTL_NOTIF_RECV = NativeConstants.SECCOMP_IOCTL_NOTIF_RECV

        private const val ECONNRESET = 104
        private val logger = java.util.logging.Logger.getLogger(ProfilerSessionHandler::class.java.name)

        private const val SYS_OPEN = 2
        private const val SYS_OPENAT = 257
        private const val SYS_OPENAT2 = 437

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private val safeBypassPaths = mutableListOf<Path>().apply {
            try {
                val javaHome = Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize()
                add(javaHome)

                val cp = System.getProperty("java.class.path")
                if (cp != null) {
                    val cpEntries = cp.split(java.io.File.pathSeparator)
                    for (entry in cpEntries) {
                        if (entry.isNotEmpty()) {
                            try {
                                val cpPath = Paths.get(entry).toAbsolutePath().normalize()
                                add(cpPath)
                            } catch (ignored: Exception) {}
                        }
                    }
                }

                // Add javaagent jars to prevent deadlocks during agent instrumentation
                val jvmArgs = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
                for (arg in jvmArgs) {
                    if (arg.startsWith("-javaagent:")) {
                        val agentPath = arg.substringAfter("-javaagent:").substringBefore("=")
                        if (agentPath.isNotEmpty()) {
                            try {
                                val p = Paths.get(agentPath).toAbsolutePath().normalize()
                                add(p)
                            } catch (ignored: Exception) {}
                        }
                    }
                }

                // Add CI-specific build directories and test-framework caches to prevent deadlock
                try {
                    add(Paths.get("build").toAbsolutePath().normalize())
                    add(Paths.get(".gradle").toAbsolutePath().normalize())
                } catch (ignored: Exception) {}

                // Add /proc and /sys virtual filesystems to prevent GC/JIT thread deadlocks
                try {
                    add(Paths.get("/proc").toAbsolutePath().normalize())
                    add(Paths.get("/sys").toAbsolutePath().normalize())
                } catch (ignored: Exception) {}
            } catch (e: Exception) {
                // Fail-safe
            }
        }
    }

    /**
     * Main entry point for the session reactor iteration.
     * Checks for parent shutdown and incoming seccomp notifications.
     */
    @Suppress("NestedBlockDepth", "ReturnCount", "LongMethod")
    context(arena: NativeArena)
    fun handleActiveListener(
        pollFds: ManagedSegment,
        ackBuf: ManagedSegment,
        notif: ManagedSegment,
        resp: ManagedSegment,
        socketPollFd: ManagedSegment,
    ): LoopAction {
        val errorOrHup = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()

        val socketRevents = pollFds.readShort(POLLFD_STRUCT_SIZE + POLLFD_REVENTS_OFF).toInt()
        if ((socketRevents and NativeConstants.POLLIN.toInt()) != 0 || (socketRevents and errorOrHup) != 0) {
            val isDeadOrShutdown = (socketRevents and errorOrHup) != 0 || handleShutdownRequest(ackBuf)
            if (isDeadOrShutdown) {
                state = ProfilerState.Terminated(socketFd, listenerFd)
                return LoopAction.Shutdown
            }
        }

        val listenerRevents = pollFds.readShort(POLLFD_REVENTS_OFF).toInt()
        if ((listenerRevents and errorOrHup) != 0) {
            state = ProfilerState.Terminated(socketFd, listenerFd)
            return LoopAction.Shutdown
        }

        if ((listenerRevents and NativeConstants.POLLIN.toInt()) != 0) {
            notif.native.fill(0)
            val recvRes = LinuxNative.withTransaction { ioOps.raw.ioctl(listenerFd, SECCOMP_IOCTL_NOTIF_RECV, notif) }
            if (recvRes is LinuxNative.SyscallResult.Success) {
                val ok = processNotification(notif, resp, ackBuf, socketPollFd)
                if (!ok) {
                    System.err.println("[DAEMON-WARN] Failed to process notification. Terminating session.")
                    state = ProfilerState.Terminated(socketFd, listenerFd)
                }
            }
            if (state is ProfilerState.Terminated) return LoopAction.Break
        }
        return LoopAction.Continue
    }

    @Suppress("ReturnCount")
    private fun handleShutdownRequest(ackBuf: ManagedSegment): Boolean {
        val res = ioOps.recv(socketFd, ackBuf, 1L, 0)
        return res.map { value ->
            if (value > 0) {
                val command = ackBuf.readByte(0L)
                if (command == SHUTDOWN_COMMAND_BYTE) {
                    onShutdown("Parent Command")
                    true
                } else {
                    false
                }
            } else {
                true // parent socket closed
            }
        }.recover { _, _ -> false }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount", "CyclomaticComplexMethod")
    context(arena: NativeArena)
    internal fun processNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
        ackBuf: ManagedSegment,
        socketPollFd: ManagedSegment,
    ): Boolean {
        val currentState = state as? ProfilerState.ActiveSession ?: return false

        val id = notif.readLong(NOTIF_ID_OFF)
        val pidVal = notif.readInt(NOTIF_PID_OFF)
        val nr = notif.readInt(NOTIF_NR_OFF)

        System.err.println("[DAEMON-DEBUG] Received notification: id=$id, pid=$pidVal, nr=$nr")
        val handshake = HandshakeSession.Active(id, listenerFd)

        var continueSent = false

        try {
            ledger.record(SessionEvent.Notified(System.nanoTime(), pidVal.toLong(), nr.toLong()))

            val argsList = mutableListOf<Long>()
            for (i in 0 until MAX_SYSCALL_ARGS) {
                argsList.add(notif.readLong(NOTIF_ARGS_OFF + i * 8L))
            }

            // RESOLVE: Transform raw event into a resolved event (read path from tracee memory).
            val resolver = SyscallPathResolver(memoryReader, ledger)
            val rawEvent = SyscallEvent<SyscallEventState.Raw>(
                tid = Tid(pidVal),
                syscallName = syscallMap[nr] ?: "SYSCALL_$nr",
                args = argsList
            )
            val resolvedEvent = resolver.resolve(rawEvent)

            // Optimisation: skip event delivery for JVM-internal paths that generate noise
            // (JDK home, classpath, /proc, /sys).
            if ((nr == SYS_OPEN || nr == SYS_OPENAT || nr == SYS_OPENAT2) && resolvedEvent.paths.isNotEmpty()) {
                val pathStr = resolvedEvent.paths.first()
                try {
                    val path = Paths.get(pathStr).toAbsolutePath().normalize()
                    val matched = safeBypassPaths.any { bypassPath ->
                        path.startsWith(bypassPath) || path == bypassPath
                    }
                    System.err.println("[DAEMON-DEBUG] Noise-filter check: path=$pathStr, skip=$matched")
                    if (matched) {
                        responder.sendSeccompContinue(handshake.acknowledged(), resp)
                        return true
                    }
                } catch (ignored: Exception) {}
            }

            val notifiedState = currentState.notified(id, resolvedEvent)
            val waitingState = notifiedState.waitingForAck()
            state = waitingState

            socketPollFd.writeInt(Layouts.POLLFD_FD_OFFSET, socketFd.value)
            socketPollFd.writeShort(Layouts.POLLFD_EVENTS_OFFSET, NativeConstants.POLLIN)

            // DELIVER: Write event to JVM listener socket.
            System.err.println("[DAEMON-DEBUG] Sending event to JVM listener: tid=$pidVal, syscall=${resolvedEvent.syscallName}, paths=${resolvedEvent.paths}")
            publisher.sendTraceEvent(socketFd, resolvedEvent)
            System.err.println("[DAEMON-DEBUG] Event sent to JVM listener.")
            ledger.record(SessionEvent.EventSent(System.nanoTime(), pidVal.toLong()))

            // HANDSHAKE: Wait for the JVM listener to ACK the event before letting the tracee continue.
            val result = handshake.performHandshake(socketFd, ioOps, socketPollFd, ackBuf, onShutdown)
            return when (result) {
                is HandshakeSession.Success -> {
                    ledger.record(SessionEvent.AckReceived(System.nanoTime(), pidVal.toLong()))
                    state = waitingState.acknowledged()
                    responder.sendSeccompContinue(result, resp)
                    continueSent = true
                    ledger.record(SessionEvent.ContinueReplied(System.nanoTime(), pidVal.toLong(), 0L))
                    true
                }
                is HandshakeSession.Failed -> {
                    System.err.println("[DAEMON-WARN] Handshake failed or shutdown triggered")
                    state = waitingState.terminate()
                    responder.sendSeccompError(result, resp, ECONNRESET)
                    ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pidVal.toLong(), ECONNRESET))
                    false
                }
                else -> {
                    state = ProfilerState.Terminated(socketFd, listenerFd)
                    false
                }
            }
        } catch (e: Throwable) {
            logger.severe {
                "Exception in processNotification: ${e.message}. Dumping SessionEventLedger:\n" +
                    ledger.dump().joinToString("\n")
            }
            if (continueSent) {
                state = ProfilerState.ActiveSession(socketFd, listenerFd)
                return true
            }
            responder.sendSeccompError(handshake.failed(), resp, ECONNRESET)
            ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pidVal.toLong(), ECONNRESET))
            return false
        }
    }
}
