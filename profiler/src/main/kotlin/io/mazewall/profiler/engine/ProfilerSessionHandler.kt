package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.ffi.NativeConstants
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
    fun handleActiveListener(
        pollFds: MemorySegment,
        ackBuf: MemorySegment,
        notif: MemorySegment,
        resp: MemorySegment,
    ): LoopAction {
        val currentState = state
        if (currentState is ProfilerState.Terminated) {
            return LoopAction.Break
        }

        val socketRevents = pollFds.get(ValueLayout.JAVA_SHORT, POLLFD_REVENT_DATA_OFF).toInt()
        val errorOrHup = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
        if ((socketRevents and (NativeConstants.POLLIN.toInt() or errorOrHup)) != 0) {
            val isDeadOrShutdown = (socketRevents and errorOrHup) != 0 || handleShutdownRequest(ackBuf)
            if (isDeadOrShutdown) {
                state = ProfilerState.Terminated(socketFd, listenerFd)
                return LoopAction.Shutdown
            }
        }

        val listenerRevents = pollFds.get(ValueLayout.JAVA_SHORT, POLLFD_REVENTS_OFF).toInt()
        if ((listenerRevents and errorOrHup) != 0) {
            state = ProfilerState.Terminated(socketFd, listenerFd)
            return LoopAction.Shutdown
        }

        if ((listenerRevents and NativeConstants.POLLIN.toInt()) != 0) {
            notif.fill(0)
            val recvRes = ioOps.ioctl(listenerFd, SECCOMP_IOCTL_NOTIF_RECV, notif)
            recvRes.onSuccess {
                if (!processNotification(notif, resp)) {
                    state = ProfilerState.Terminated(socketFd, listenerFd)
                }
            }
            if (state is ProfilerState.Terminated) return LoopAction.Break
        }
        return LoopAction.Continue
    }

    @Suppress("ReturnCount")
    private fun handleShutdownRequest(ackBuf: MemorySegment): Boolean {
        val res = ioOps.recv(socketFd, ackBuf, 1L, 0)
        return res.map { value ->
            if (value > 0) {
                val command = ackBuf.get(ValueLayout.JAVA_BYTE, 0L)
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

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    internal fun processNotification(
        notif: MemorySegment,
        resp: MemorySegment,
    ): Boolean {
        val currentState = state as? ProfilerState.ActiveSession ?: return false

        val id = notif.get(ValueLayout.JAVA_LONG, NOTIF_ID_OFF)
        val pidVal = notif.get(ValueLayout.JAVA_INT, NOTIF_PID_OFF)
        val nr = notif.get(ValueLayout.JAVA_INT, NOTIF_NR_OFF)

        System.err.println("[DAEMON-DEBUG] Received notification: id=$id, pid=$pidVal, nr=$nr")
        val handshake = HandshakeSession.Active(id, listenerFd)

        // Track whether SECCOMP_USER_NOTIF_FLAG_CONTINUE was sent for this notification.
        // The kernel allows exactly one response per notification ID. If CONTINUE was already
        // sent, we must NOT call sendSeccompError in the catch block — the kernel would reject
        // it with ENOENT (stale ID), causing an exception in the exception handler itself and
        // crashing the daemon thread entirely.
        var continueSent = false

        try {
            ledger.record(SessionEvent.Notified(System.nanoTime(), pidVal.toLong(), nr.toLong()))

            val args = LongArray(MAX_SYSCALL_ARGS)
            for (i in 0 until MAX_SYSCALL_ARGS) {
                args[i] = notif.get(ValueLayout.JAVA_LONG, NOTIF_ARGS_OFF + i * ValueLayout.JAVA_LONG.byteSize())
            }

            // RESOLVE: Transform raw event into a resolved event (read path from tracee memory).
            // process_vm_readv is called here while the tracee is still suspended, guaranteeing
            // that the path argument is still valid in the tracee's address space.
            val resolver = SyscallPathResolver(memoryReader, ledger)
            val resolvedEvent = resolver.resolve(event = SyscallEvent<SyscallEventState.Raw>(
                tid = Tid(pidVal),
                syscallName = syscallMap[nr] ?: "SYSCALL_$nr",
                args = args.toList()
            ))

            // FIRE-AND-FORGET: Release the tracee thread immediately after path resolution.
            //
            // The tracee is unblocked here — before any IPC to the JVM listener. This eliminates
            // the synchronous ACK round-trip that was the source of all profiler deadlocks:
            //
            //   OLD (broken): tracee blocked → daemon writes event → JVM reads event →
            //                 JVM sends ACK → daemon receives ACK → daemon sends CONTINUE
            //   NEW (correct): tracee blocked → daemon resolves path → daemon sends CONTINUE →
            //                  tracee unblocked → daemon delivers event to JVM asynchronously
            //
            // path resolution via process_vm_readv is still safe because CONTINUE is sent AFTER
            // the read, not before.
            System.err.println("[DAEMON-DEBUG] Releasing tracee tid=$pidVal (fire-and-forget CONTINUE)")
            val notifiedState = currentState.notified(id, resolvedEvent)
            val waitingState = notifiedState.waitingForAck()
            state = waitingState
            responder.sendSeccompContinue(handshake.acknowledged(), resp)
            continueSent = true
            ledger.record(SessionEvent.ContinueReplied(System.nanoTime(), pidVal.toLong(), 0L))
            state = waitingState.acknowledged()

            // Optimisation: skip event delivery for JVM-internal paths that generate noise
            // (JDK home, classpath, /proc, /sys). The tracee is already running; this is
            // purely an IPC volume reduction, not a deadlock-prevention measure.
            if ((nr == SYS_OPEN || nr == SYS_OPENAT || nr == SYS_OPENAT2) && resolvedEvent.paths.isNotEmpty()) {
                val pathStr = resolvedEvent.paths.first()
                try {
                    val path = java.nio.file.Paths.get(pathStr).toAbsolutePath().normalize()
                    val matched = safeBypassPaths.any { bypassPath ->
                        path.startsWith(bypassPath) || path == bypassPath
                    }
                    System.err.println("[DAEMON-DEBUG] Noise-filter check: path=$pathStr, skip=$matched")
                    if (matched) return true
                } catch (ignored: Exception) {}
            }

            // DELIVER: Write event to JVM listener socket (non-blocking from tracee perspective).
            System.err.println("[DAEMON-DEBUG] Sending event to JVM listener: tid=$pidVal, syscall=${resolvedEvent.syscallName}, paths=${resolvedEvent.paths}")
            publisher.sendTraceEvent(socketFd, resolvedEvent)
            System.err.println("[DAEMON-DEBUG] Event sent to JVM listener.")
            ledger.record(SessionEvent.EventSent(System.nanoTime(), pidVal.toLong()))

            return true
        } catch (e: Throwable) {
            logger.severe {
                "Exception in processNotification: ${e.message}. Dumping SessionEventLedger:\n" +
                    ledger.dump().joinToString("\n")
            }
            if (continueSent) {
                // CONTINUE was already sent — the tracee is running. We cannot respond again.
                // Log the delivery failure but keep the session alive: return true so the caller
                // does not terminate the session over a JVM-side delivery error.
                state = ProfilerState.ActiveSession(socketFd, listenerFd)
                return true
            }
            // CONTINUE was not yet sent — error out and release the tracee.
            responder.sendSeccompError(handshake.failed(), resp, ECONNRESET)
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

                // Add the project root directory to bypass all project classes and build artifacts
                try {
                    val userDir = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
                    add(userDir)
                } catch (ignored: Exception) {}
            } catch (e: Exception) {
                // Fail-safe
            }
        }
    }
}
