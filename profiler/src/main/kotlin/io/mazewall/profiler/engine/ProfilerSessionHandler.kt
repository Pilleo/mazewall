package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import java.lang.foreign.Arena
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.unwrap
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeShort
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.readShort
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.fill
import io.mazewall.map
import io.mazewall.onSuccess
import io.mazewall.recover
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Result of a reactor loop iteration.
 */
internal sealed class LoopAction {
    object Continue : LoopAction()
    object Break : LoopAction()
    object Shutdown : LoopAction()
}

/**
 * Internal logic for handling active seccomp listeners and shutdown requests.
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
    val ledger = SessionEventLedger()

    var state: ProfilerState = ProfilerState.ActiveSession(socketFd, listenerFd)
        private set

    @Suppress("ReturnCount")
    context(arena: NativeArena)
    fun handleActiveListener(
        pollFds: ManagedSegment,
        ackBuf: ManagedSegment,
        notif: ManagedSegment,
        resp: ManagedSegment,
        socketPollFd: ManagedSegment,
    ): LoopAction {
        val currentState = state
        if (currentState is ProfilerState.Terminated) {
            return LoopAction.Break
        }

        val socketRevents = pollFds.readShort(POLLFD_REVENT_DATA_OFF).toInt()
        val errorOrHup = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
        if ((socketRevents and (NativeConstants.POLLIN.toInt() or errorOrHup)) != 0) {
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
            notif.fill(0)
            val recvRes = LinuxNative.withTransaction { ioOps.raw.ioctl(listenerFd, SECCOMP_IOCTL_NOTIF_RECV, notif) }
            recvRes.onSuccess {
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
        val res = ioOps.recv(socketFd, ackBuf.unwrap, 1L, 0)
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

            val args = LongArray(MAX_SYSCALL_ARGS)
            for (i in 0 until MAX_SYSCALL_ARGS) {
                args[i] = notif.readLong(NOTIF_ARGS_OFF + i * 8L)
            }

            // RESOLVE: Transform raw event into a resolved event (read path from tracee memory).
            val resolver = SyscallPathResolver(memoryReader, ledger)
            val resolvedEvent = with(arena) {
                resolver.resolve(
                    event = SyscallEvent<SyscallEventState.Raw>(
                        tid = Tid(pidVal),
                        syscallName = syscallMap[nr] ?: "SYSCALL_$nr",
                        args = args.toList()
                    )
                )
            }

            // Optimisation: skip event delivery for JVM-internal paths that generate noise
            // (JDK home, classpath, /proc, /sys).
            if ((nr == SYS_OPEN || nr == SYS_OPENAT || nr == SYS_OPENAT2) && resolvedEvent.paths.isNotEmpty()) {
                val pathStr = resolvedEvent.paths.first()
                try {
                    val path = java.nio.file.Paths.get(pathStr).toAbsolutePath().normalize()
                    val matched = safeBypassPaths.any { bypassPath ->
                        path.startsWith(bypassPath) || path == bypassPath
                    }
                    System.err.println("[DAEMON-DEBUG] Noise-filter check: path=$pathStr, skip=$matched")
                    if (matched) {
                        with(arena.unwrap) {
                            responder.sendSeccompContinue(handshake.acknowledged(), resp.unwrap)
                        }
                        return true
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: java.nio.channels.ClosedByInterruptException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (ignored: Exception) {}
            }

            val notifiedState = currentState.notified(id, resolvedEvent)
            val waitingState = notifiedState.waitingForAck()
            state = waitingState

            socketPollFd.writeInt(POLLFD_FD_OFF, socketFd.value)
            socketPollFd.writeShort(POLLFD_EVENTS_OFF, NativeConstants.POLLIN)

            // DELIVER: Write event to JVM listener socket.
            System.err.println("[DAEMON-DEBUG] Sending event to JVM listener: tid=$pidVal, syscall=${resolvedEvent.syscallName}, paths=${resolvedEvent.paths}")
            with(arena.unwrap) {
                publisher.sendTraceEvent(socketFd, resolvedEvent)
            }
            System.err.println("[DAEMON-DEBUG] Event sent to JVM listener.")
            ledger.record(SessionEvent.EventSent(System.nanoTime(), pidVal.toLong()))

            // HANDSHAKE: Wait for the JVM listener to ACK the event before letting the tracee continue.
            // This blocking synchronization is physically required: if the daemon sends CONTINUE immediately
            // (asynchronous fire-and-forget), the tracee thread resumes and moves past the system call frame
            // before the JVM listener thread can capture its stack trace, resulting in empty or incorrect traces.
            val result = handshake.performHandshake(socketFd, ioOps, socketPollFd.unwrap, ackBuf.unwrap, onShutdown)
            return when (result) {
                is HandshakeSession.Success -> {
                    ledger.record(SessionEvent.AckReceived(System.nanoTime(), pidVal.toLong()))
                    state = waitingState.acknowledged()
                    with(arena.unwrap) {
                        responder.sendSeccompContinue(result, resp.unwrap)
                    }
                    continueSent = true
                    ledger.record(SessionEvent.ContinueReplied(System.nanoTime(), pidVal.toLong(), 0L))
                    true
                }
                is HandshakeSession.Failed -> {
                    System.err.println("[DAEMON-WARN] Handshake failed or shutdown triggered")
                    state = waitingState.terminate()
                    with(arena.unwrap) {
                        responder.sendSeccompError(result, resp.unwrap, ECONNRESET)
                    }
                    ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pidVal.toLong(), ECONNRESET))
                    false
                }
                else -> {
                    state = ProfilerState.Terminated(socketFd, listenerFd)
                    false
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: java.nio.channels.ClosedByInterruptException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Throwable) {
            logger.severe {
                "Exception in processNotification: ${e.message}. Dumping SessionEventLedger:\n" +
                    ledger.dump().joinToString("\n")
            }
            if (continueSent) {
                state = ProfilerState.ActiveSession(socketFd, listenerFd)
                return true
            }
            with(arena.unwrap) {
                responder.sendSeccompError(handshake.failed(), resp.unwrap, ECONNRESET)
            }
            ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pidVal.toLong(), ECONNRESET))
            return false
        }
    }


    companion object {
        private const val ECONNRESET = 104
        private val logger = java.util.logging.Logger.getLogger(ProfilerSessionHandler::class.java.name)

        private const val SYS_OPEN = 2
        private const val SYS_OPENAT = 257
        private const val SYS_OPENAT2 = 437

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private val safeBypassPaths = mutableListOf<java.nio.file.Path>().apply {
            try {
                val javaHome = java.nio.file.Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize()
                add(javaHome)

                val cp = System.getProperty("java.class.path")
                if (cp != null) {
                    val cpEntries = cp.split(java.io.File.pathSeparator)
                    for (entry in cpEntries) {
                        if (entry.isNotEmpty()) {
                            try {
                                val cpPath = java.nio.file.Paths.get(entry).toAbsolutePath().normalize()
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
                                val p = java.nio.file.Paths.get(agentPath).toAbsolutePath().normalize()
                                add(p)
                            } catch (ignored: Exception) {}
                        }
                    }
                }

                // Add CI-specific build directories and test-framework caches to prevent deadlock
                try {
                    add(java.nio.file.Paths.get("build").toAbsolutePath().normalize())
                    add(java.nio.file.Paths.get(".gradle").toAbsolutePath().normalize())
                } catch (ignored: Exception) {}

                // Add /proc and /sys virtual filesystems to prevent GC/JIT thread deadlocks
                try {
                    add(java.nio.file.Paths.get("/proc").toAbsolutePath().normalize())
                    add(java.nio.file.Paths.get("/sys").toAbsolutePath().normalize())
                } catch (ignored: Exception) {}
            } catch (e: Exception) {
                // Fail-safe
            }
        }
    }
}
