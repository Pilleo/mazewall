package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.Pid
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
    private val socketFd: LinuxNative.FileDescriptor,
    private val listenerFd: LinuxNative.FileDescriptor,
    private val transport: ProfilerTransport,
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
        socketPollFd: MemorySegment,
    ): LoopAction {
        val currentState = state
        if (currentState is ProfilerState.Terminated) {
            return LoopAction.Break
        }

        val socketRevents = pollFds.get(ValueLayout.JAVA_SHORT, POLLFD_REVENT_DATA_OFF)
        if ((socketRevents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
            if (handleShutdownRequest(ackBuf)) {
                state = ProfilerState.Terminated
                return LoopAction.Shutdown
            }
        }

        val listenerRevents = pollFds.get(ValueLayout.JAVA_SHORT, POLLFD_REVENTS_OFF)
        if ((listenerRevents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
            notif.fill(0)
            val recvRes = transport.ioctl(listenerFd, SECCOMP_IOCTL_NOTIF_RECV, notif)
            recvRes.onSuccess {
                if (!processNotification(notif, resp, ackBuf, socketPollFd)) {
                    state = ProfilerState.Terminated
                }
            }
            if (state is ProfilerState.Terminated) return LoopAction.Break
        }
        return LoopAction.Continue
    }

    @Suppress("ReturnCount")
    private fun handleShutdownRequest(ackBuf: MemorySegment): Boolean {
        val res = transport.recv(socketFd, ackBuf, 1L, 0)
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

    internal fun processNotification(
        notif: MemorySegment,
        resp: MemorySegment,
        ackBuf: MemorySegment,
        socketPollFd: MemorySegment,
    ): Boolean {
        // 1. RECEIVE: Parse raw registers into a SyscallEvent<Raw>
        val id = notif.get(ValueLayout.JAVA_LONG, NOTIF_ID_OFF)
        val pidVal = notif.get(ValueLayout.JAVA_INT, NOTIF_PID_OFF)
        val nr = notif.get(ValueLayout.JAVA_INT, NOTIF_NR_OFF)
        ledger.record(SessionEvent.Notified(System.nanoTime(), pidVal.toLong(), nr.toLong()))

        val args = LongArray(MAX_SYSCALL_ARGS)
        for (i in 0 until MAX_SYSCALL_ARGS) {
            args[i] = notif.get(ValueLayout.JAVA_LONG, NOTIF_ARGS_OFF + i * ValueLayout.JAVA_LONG.byteSize())
        }

        val rawEvent = SyscallEvent<SyscallEventState.Raw>(
            pid = pidVal,
            syscallName = syscallMap[nr] ?: "SYSCALL_$nr",
            args = args
        )

        // 2. RESOLVE: Transform Raw event into Resolved event (path resolution)
        val resolver = SyscallPathResolver(memoryReader, ledger)
        val resolvedEvent = resolver.resolve(rawEvent)

        // 3. NOTIFY: Prepare handshake and notify JVM listener
        val handshake = HandshakeSession.Active(id, listenerFd)
        state = ProfilerState.Notified(socketFd, listenerFd, id, resolvedEvent)

        socketPollFd.set(ValueLayout.JAVA_INT, POLLFD_FD_OFF, socketFd.value)
        socketPollFd.set(ValueLayout.JAVA_SHORT, POLLFD_EVENTS_OFF, NativeConstants.POLLIN.toShort())

        state = ProfilerState.WaitingForAck(socketFd, listenerFd, id)

        @Suppress("TooGenericExceptionCaught")
        try {
            transport.sendTraceEvent(socketFd, resolvedEvent)
            ledger.record(SessionEvent.EventSent(System.nanoTime(), pidVal.toLong()))

            // 4. HANDSHAKE: Delegate IPC protocol to the state machine
            val result = handshake.performHandshake(socketFd, transport, socketPollFd, ackBuf, onShutdown)

            // 5. REPLY: Finalize based on handshake outcome
            return when (result) {
                is HandshakeSession.Success -> {
                    ledger.record(SessionEvent.AckReceived(System.nanoTime(), pidVal.toLong()))
                    state = ProfilerState.ActiveSession(socketFd, listenerFd)
                    transport.sendSeccompContinue(result, resp)
                    ledger.record(SessionEvent.ContinueReplied(System.nanoTime(), pidVal.toLong(), 0L))
                    true
                }

                is HandshakeSession.Failed -> {
                    logger.warning {
                        "ACK wait failed (timeout or error). Dumping SessionEventLedger:\n" +
                            ledger.dump().joinToString("\n")
                    }
                    state = ProfilerState.Terminated
                    transport.sendSeccompError(result, resp, ECONNRESET)
                    ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pidVal.toLong(), ECONNRESET))
                    false
                }

                else -> {
                    state = ProfilerState.Terminated
                    false
                }
            }
        } catch (e: Throwable) {
            logger.severe {
                "Exception in processNotification: ${e.message}. Dumping SessionEventLedger:\n" +
                    ledger.dump().joinToString("\n")
            }
            transport.sendSeccompError(handshake.failed(), resp, ECONNRESET)
            ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pidVal.toLong(), ECONNRESET))
            throw e
        }
    }

    companion object {
        private const val POLL_ACK_TIMEOUT_MS = 5000
        private const val ECONNRESET = 104
        private const val RETRY_SIGNAL = -1L
        private const val INTERNAL_ERROR_SIGNAL = -2L
        private val logger = java.util.logging.Logger.getLogger(ProfilerSessionHandler::class.java.name)
    }
}
