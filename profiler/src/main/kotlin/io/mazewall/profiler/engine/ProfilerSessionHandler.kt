package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import java.io.IOException
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
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.SegmentPool
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

import io.mazewall.profiler.engine.SeccompResponder
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.platform.seccomp.daemon.SeccompNotifHandler


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
    private val parser: SeccompNotificationParser = RealSeccompNotificationParser,
    private val onShutdown: (String) -> Unit,
) : AutoCloseable, SeccompNotifHandler {

    val ledger = SessionEventLedger()

    private val sessionArena = NativeArena.ofConfined()
    val ackBuf: ManagedSegment = sessionArena.allocate(1L)
    val socketPollFd: ManagedSegment = sessionArena.allocate(Layouts.POLLFD)

    private val resolver = SyscallPathResolver(memoryReader, ledger)

    private var isPassThrough = false

    var state: ProfilerState = ProfilerState.ActiveSession(socketFd, listenerFd)
        private set

    override fun close() {
        sessionArena.close()
    }

    /**
     * Processes a single incoming seccomp notification.
     *
     * ### ⚠️ FFM Memory Safety & Lifetime Invariant:
     * To prevent off-heap/native memory segment leaks, high GC pressure, and lifetime issues,
     * any raw seccomp_data structural slices or other native/off-heap memory resolved inside this method
     * **must be strictly materialized into JVM heap objects** before crossing the [TraceEvent] or
     * [SyscallEvent] boundaries (which is done here when [SyscallPathResolver.resolve] is called
     * and the resulting event is published via [TraceEventPublisher.sendTraceEvent]).
     *
     * ### 🚀 MM Optimization & Arena Reuse:
     * This method reuses the context-passed `arena` (which is the short-lived `iterationArena`
     * managed and closed at the end of every loop iteration in [ProfilerDaemonEngine.handleSession]).
     * This ensures that all transient native allocations (e.g., string reading, socket polling structures)
     * are deterministically freed when the iteration completes, completely eliminating the overhead of
     * creating a new confined arena per notification or operation.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "CyclomaticComplexMethod")
    context(arena: NativeArena)
    override fun processNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>
    ): NotifResult {
        val currentState = state as? ProfilerState.ActiveSession ?: return NotifResult.TERMINATE

        val parsedNotif = parser.readNotif(notif)
        val id = parsedNotif.id
        val pidVal = parsedNotif.pid
        val nr = parsedNotif.nr

        System.err.println("[DAEMON-DEBUG] Received notification: id=$id, pid=$pidVal, nr=$nr")
        val handshake = HandshakeSession.Active(id, listenerFd)

        var continueSent = false

        try {
            ledger.record(SessionEvent.Notified(System.nanoTime(), pidVal.toLong(), nr.toLong()))

            val args = parsedNotif.args
            val syscallName = syscallMap[nr] ?: "SYSCALL_$nr"

            // RESOLVE: Transform raw event into a resolved event (read path from tracee memory).
            val paths = with(arena) {
                resolver.resolvePaths(Tid(pidVal), syscallName, args)
            }

            val argList = ArrayList<Long>(args.size).apply {
                for (a in args) {
                    add(a)
                }
            }

            val resolvedEvent = SyscallEvent<SyscallEventState.Resolved>(
                tid = Tid(pidVal),
                syscallName = syscallName,
                args = argList,
                paths = paths
            )

            // Optimisation: skip event delivery for JVM-internal paths that generate noise
            // (JDK home, classpath, /proc, /sys).
            if (checkAndBypassNoisePath(arena, nr, resolvedEvent, handshake, resp)) {
                return NotifResult.HANDLED
            }

            val notifiedState = currentState.notified(id, resolvedEvent)
            val waitingState = notifiedState.waitingForAck()
            state = waitingState

            parser.writeSocketPoll(socketPollFd, socketFd.value, NativeConstants.POLLIN)

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
                    NotifResult.HANDLED
                }
                is HandshakeSession.Failed -> {
                    System.err.println("[DAEMON-WARN] Handshake failed or shutdown triggered")
                    state = waitingState.terminate()
                    with(arena.unwrap) {
                        responder.sendSeccompError(result, resp.unwrap, ECONNRESET)
                    }
                    ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pidVal.toLong(), ECONNRESET))
                    NotifResult.TERMINATE
                }
                is HandshakeSession.PassedThrough -> {
                    System.err.println("[DAEMON-DEBUG] Handshake returned PassThrough")
                    NotifResult.PASS_THROUGH
                }
                else -> {
                    state = ProfilerState.Terminated(socketFd, listenerFd)
                    NotifResult.TERMINATE
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: java.nio.channels.ClosedByInterruptException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: IOException) {
            logger.severe {
                "IOException in processNotification: ${e.message}. Dumping SessionEventLedger:\n" +
                    ledger.dump().joinToString("\n")
            }
            if (continueSent) {
                state = ProfilerState.ActiveSession(socketFd, listenerFd)
                return NotifResult.HANDLED
            }
            try {
                with(arena.unwrap) {
                    responder.sendSeccompError(handshake.failed(), resp.unwrap, ECONNRESET)
                }
            } catch (ignored: Throwable) {}
            ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pidVal.toLong(), ECONNRESET))
            return NotifResult.TERMINATE
        } catch (e: Throwable) {
            logger.severe {
                "Structural or unrecoverable error in processNotification: ${e.message}. Dumping SessionEventLedger:\n" +
                    ledger.dump().joinToString("\n")
            }
            throw e
        }
    }

    /**
     * Noise filter bypass logic helper.
     */
    private fun checkAndBypassNoisePath(
        arena: NativeArena,
        nr: Int,
        resolvedEvent: SyscallEvent<SyscallEventState.Resolved>,
        handshake: HandshakeSession.Active,
        resp: ManagedSegment,
    ): Boolean {
        if ((nr == SYS_OPEN || nr == SYS_OPENAT || nr == SYS_OPENAT2) && resolvedEvent.paths.isNotEmpty()) {
            val pathStr = resolvedEvent.paths.first()
            try {
                val normalizedPathStr = PathNormalizerHelper.normalizePath(pathStr)
                val matched = safeBypassPaths.any { bypassPath ->
                    PathNormalizerHelper.pathStartsWith(normalizedPathStr, bypassPath)
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
        return false
    }


    companion object {
        private const val SHUTDOWN_COMMAND_BYTE: Byte = 0x53.toByte() // 'S'
        private const val PASS_THROUGH_COMMAND_BYTE: Byte = 0x54.toByte() // 'T' / 'P'

        private const val ECONNRESET = 104
        private val logger = java.util.logging.Logger.getLogger(ProfilerSessionHandler::class.java.name)

        private const val SYS_OPEN = 2
        private const val SYS_OPENAT = 257
        private const val SYS_OPENAT2 = 437

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private val safeBypassPaths = mutableListOf<String>().apply {
            try {
                val javaHome = java.nio.file.Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize().toString()
                add(javaHome)

                val cp = System.getProperty("java.class.path")
                if (cp != null) {
                    val cpEntries = cp.split(java.io.File.pathSeparator)
                    for (entry in cpEntries) {
                        if (entry.isNotEmpty()) {
                            try {
                                val cpPath = java.nio.file.Paths.get(entry).toAbsolutePath().normalize().toString()
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
                                val p = java.nio.file.Paths.get(agentPath).toAbsolutePath().normalize().toString()
                                add(p)
                            } catch (ignored: Exception) {}
                        }
                    }
                }

                // Add CI-specific build directories and test-framework caches to prevent deadlock
                try {
                    add(java.nio.file.Paths.get("build").toAbsolutePath().normalize().toString())
                    add(java.nio.file.Paths.get(".gradle").toAbsolutePath().normalize().toString())
                } catch (ignored: Exception) {}

                // Add /proc and /sys virtual filesystems to prevent GC/JIT thread deadlocks
                try {
                    add(java.nio.file.Paths.get("/proc").toAbsolutePath().normalize().toString())
                    add(java.nio.file.Paths.get("/sys").toAbsolutePath().normalize().toString())
                } catch (ignored: Exception) {}
            } catch (e: Exception) {
                // Fail-safe
            }
        }
    }
}
