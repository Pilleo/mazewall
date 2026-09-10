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

    fun run() {
        val primaryFailure = captureFailure(::serve)
        val cleanupFailure = captureFailure(transport::close)
        if (primaryFailure != null) cleanupFailure?.takeIf { it !== primaryFailure }?.let(primaryFailure::addSuppressed)
        val terminal = primaryFailure ?: cleanupFailure
        if (terminal != null) throw terminal
    }

    private fun serve() {
        try {
            serve@ while (state != PortalWorkerState.Stopped) {
                when (val received = transport.receive()) {
                    PortalWorkerReceive.Idle -> state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.IdleTick).state
                    PortalWorkerReceive.PeerClosed -> {
                        state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.PeerClosed).state
                        break@serve
                    }
                    is PortalWorkerReceive.Failure -> {
                        state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.FatalFailure).state
                        throw received.cause
                    }
                    is PortalWorkerReceive.Frame -> handle(received)
                }
            }
        } finally {
            if (state != PortalWorkerState.Stopped) {
                state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.FatalFailure).state
            }
        }
    }

    private fun captureFailure(action: () -> Unit): Throwable? = runCatching(action).exceptionOrNull()

    // Dispatch, capability retirement, and reply transmission are distinct terminal boundaries;
    // fatal failures must retain their identity and a failed send must never append another frame.
    @Suppress("TooGenericExceptionCaught")
    private fun handle(received: PortalWorkerReceive.Frame) {
        val transition = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.FrameReceived(received.frame))
        state = transition.state
        val dispatch = transition as? PortalWorkerTransition.Dispatch ?: run {
            Capability.closeAll(received.capabilities)
            return
        }
        var result: ByteArray? = null
        var dispatchFailure: Throwable? = null
        try {
            result = dispatcher.dispatch(
                dispatch.request.method,
                dispatch.request.payload.copyToByteArray(),
                received.capabilities,
            )
        } catch (failure: Throwable) {
            dispatchFailure = unwrapInvocation(failure)
        }
        val capabilityFailure = captureFailure { Capability.closeAll(received.capabilities) }
        val actualFailure = dispatchFailure
        if (actualFailure != null && actualFailure !is Exception) {
            state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.FatalDispatchFailed).state
            capabilityFailure?.takeIf { it !== actualFailure }?.let(actualFailure::addSuppressed)
            raiseWorkerFailure(actualFailure)
        }
        if (capabilityFailure != null) {
            state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.FatalDispatchFailed).state
            actualFailure?.takeIf { it !== capabilityFailure }?.let(capabilityFailure::addSuppressed)
            raiseWorkerFailure(capabilityFailure)
        }
        val dispatchEvent = if (actualFailure == null) {
            PortalWorkerEvent.DispatchSucceeded(PortalPayload(checkNotNull(result)))
        } else {
            val message = (actualFailure.message ?: actualFailure::class.java.simpleName).toByteArray(StandardCharsets.UTF_8)
            PortalWorkerEvent.DispatchFailed(PortalPayload(message))
        }
        val sending = PortalWorkerMachine.evaluate(state, dispatchEvent) as PortalWorkerTransition.Send
        state = sending.state
        try {
            transport.send(sending.frame)
            state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.ReplySucceeded).state
        } catch (failure: Exception) {
            state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.ReplyFailed(failure.message ?: "reply failed")).state
            raiseWorkerFailure(failure)
        }
    }

    private tailrec fun unwrapInvocation(failure: Throwable): Throwable =
        if (failure is java.lang.reflect.InvocationTargetException && failure.targetException != null) {
            unwrapInvocation(failure.targetException)
        } else {
            failure
        }
}

private fun raiseWorkerFailure(failure: Throwable): Nothing = throw failure
