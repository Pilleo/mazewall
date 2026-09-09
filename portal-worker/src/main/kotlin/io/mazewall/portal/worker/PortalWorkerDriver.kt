package io.mazewall.portal.worker

import io.mazewall.portal.Capability
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalPayload
import java.nio.charset.StandardCharsets

internal sealed interface PortalWorkerReceive {
    data class Frame(
        val frame: PortalFrame,
        val capabilities: List<Capability.ReadFd> = emptyList(),
    ) : PortalWorkerReceive

    data object Idle : PortalWorkerReceive

    data object PeerClosed : PortalWorkerReceive

    data class Failure(
        val cause: Exception,
    ) : PortalWorkerReceive
}

internal interface PortalWorkerTransport : AutoCloseable {
    fun receive(): PortalWorkerReceive

    fun send(frame: PortalFrame)
}

internal fun interface PortalWorkerDispatcher {
    fun dispatch(
        method: PortalMethod,
        payload: ByteArray,
        capabilities: List<Capability.ReadFd>,
    ): ByteArray
}

/** Authoritative one-request-at-a-time worker loop with injected I/O and dispatch boundaries. */
internal class PortalWorkerDriver(
    private val transport: PortalWorkerTransport,
    private val dispatcher: PortalWorkerDispatcher,
) {
    var state: PortalWorkerState = PortalWorkerState.AwaitingRequest
        private set

    @Suppress("TooGenericExceptionCaught") // Dispatch exceptions are converted to one error reply.
    fun run() {
        try {
            serve@ while (state != PortalWorkerState.Stopped) {
                when (val received = transport.receive()) {
                    PortalWorkerReceive.Idle -> {
                        state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.IdleTick).state
                    }
                    PortalWorkerReceive.PeerClosed -> {
                        state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.PeerClosed).state
                        break@serve
                    }
                    is PortalWorkerReceive.Failure -> {
                        state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.PeerClosed).state
                        throw received.cause
                    }
                    is PortalWorkerReceive.Frame -> {
                        handle(received)
                    }
                }
            }
        } finally {
            transport.close()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Dispatch and send are distinct exhaustive exception boundaries.
    private fun handle(received: PortalWorkerReceive.Frame) {
        val transition = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.FrameReceived(received.frame))
        state = transition.state
        val dispatch = transition as? PortalWorkerTransition.Dispatch ?: run {
            Capability.closeAll(received.capabilities)
            return
        }
        val dispatchEvent = try {
            val result = dispatcher.dispatch(
                dispatch.request.method,
                dispatch.request.payload.copyToByteArray(),
                received.capabilities,
            )
            PortalWorkerEvent.DispatchSucceeded(PortalPayload(result))
        } catch (failure: Exception) {
            val actual = unwrapInvocation(failure)
            val message = (actual.message ?: actual::class.java.simpleName).toByteArray(StandardCharsets.UTF_8)
            PortalWorkerEvent.DispatchFailed(PortalPayload(message))
        } finally {
            Capability.closeAll(received.capabilities)
        }
        val sending = PortalWorkerMachine.evaluate(state, dispatchEvent) as PortalWorkerTransition.Send
        state = sending.state
        try {
            transport.send(sending.frame)
            state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.ReplySucceeded).state
        } catch (failure: Exception) {
            state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.ReplyFailed(failure.message ?: "reply failed")).state
            throw failure
        }
    }

    private tailrec fun unwrapInvocation(failure: Exception): Exception =
        if (failure is java.lang.reflect.InvocationTargetException && failure.targetException is Exception) {
            unwrapInvocation(failure.targetException as Exception)
        } else {
            failure
        }
}
