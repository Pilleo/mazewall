package io.mazewall.portal.worker

import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalKind
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortalWorkerMachineTest {
    @Test
    fun `every state event combination has an explicit transition`() {
        val request = PortalFrame(PortalKind.Request, 5, PortalMethod.Echo, ByteArray(0), 0)
        val reply = PortalFrame(PortalKind.Response, 5, PortalMethod.Echo, ByteArray(0), 0)
        val states = listOf(PortalWorkerState.AwaitingRequest, PortalWorkerState.Dispatching(request), PortalWorkerState.SendingReply(request, reply), PortalWorkerState.Stopped)
        val events = listOf(
            PortalWorkerEvent.FrameReceived(request),
            PortalWorkerEvent.DispatchSucceeded(PortalPayload(ByteArray(0))),
            PortalWorkerEvent.DispatchFailed(PortalPayload(ByteArray(0))),
            PortalWorkerEvent.IdleTick,
            PortalWorkerEvent.PeerClosed,
            PortalWorkerEvent.ReplySucceeded,
            PortalWorkerEvent.ReplyFailed("write"),
            PortalWorkerEvent.FatalDispatchFailed,
            PortalWorkerEvent.FatalFailure,
        )
        val violation = PortalWorkerTransition.ProtocolViolation::class
        val close = PortalWorkerTransition.Close::class
        val await = PortalWorkerTransition.Await::class
        val send = PortalWorkerTransition.Send::class
        val expected = listOf(
            listOf(PortalWorkerTransition.Dispatch::class, violation, violation, await, close, violation, violation, violation, close),
            listOf(violation, send, send, await, close, violation, violation, close, close),
            listOf(violation, violation, violation, violation, close, await, close, violation, close),
            List(events.size) { PortalWorkerTransition.PostTerminalObservation::class },
        )
        states.forEachIndexed { row, state ->
            events.forEachIndexed { column, event ->
                val transition = PortalWorkerMachine.evaluate(state, event)
                assertTrue(expected[row][column].java.isInstance(transition), "$state / $event: $transition")
            }
        }
    }

    @Test
    fun `fatal transport failures close every active state without protocol violation`() {
        val request = PortalFrame(PortalKind.Request, 5, PortalMethod.Echo, ByteArray(0), 0)
        val reply = PortalFrame(PortalKind.Response, 5, PortalMethod.Echo, ByteArray(0), 0)
        for (state in listOf(PortalWorkerState.AwaitingRequest, PortalWorkerState.Dispatching(request), PortalWorkerState.SendingReply(request, reply))) {
            val transition = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.FatalFailure)
            assertInstanceOf(PortalWorkerTransition.Close::class.java, transition)
            assertEquals(PortalWorkerState.Stopped, transition.state)
        }
    }

    @Test
    fun `request dispatches then replies with the matching response`() {
        val request = PortalFrame(PortalKind.Request, 5, PortalMethod.Echo, byteArrayOf(1), 0)
        val dispatch = PortalWorkerMachine.evaluate(PortalWorkerState.AwaitingRequest, PortalWorkerEvent.FrameReceived(request))
        val dispatchState = assertInstanceOf(PortalWorkerState.Dispatching::class.java, dispatch.state)
        val reply = PortalWorkerMachine.evaluate(dispatchState, PortalWorkerEvent.DispatchSucceeded(PortalPayload(byteArrayOf(2))))

        assertTrue(dispatch is PortalWorkerTransition.Dispatch)
        val sent = reply as PortalWorkerTransition.Send
        assertInstanceOf(PortalWorkerState.SendingReply::class.java, sent.state)
        assertEquals(PortalKind.Response, sent.frame.kind)
        assertEquals(5, sent.frame.requestId)
        assertEquals(PortalMethod.Echo, sent.frame.method)
        assertTrue(
            sent.frame.payload
            .copyToByteArray()
            .contentEquals(byteArrayOf(2)),
        )
    }

    @Test
    fun `late completion is an observation without another reply or close effect`() {
        val event = PortalWorkerEvent.DispatchSucceeded(PortalPayload(byteArrayOf(2)))
        val transition = assertInstanceOf(PortalWorkerTransition.PostTerminalObservation::class.java, PortalWorkerMachine.evaluate(PortalWorkerState.Stopped, event))
        assertEquals(event, transition.event)
        assertEquals(PortalWorkerState.Stopped, transition.state)
    }

    @Test
    fun `a request is not complete until reply transmission succeeds`() {
        val request = PortalFrame(PortalKind.Request, 5, PortalMethod.Echo, ByteArray(0), 0)
        val dispatch = PortalWorkerMachine.evaluate(PortalWorkerState.AwaitingRequest, PortalWorkerEvent.FrameReceived(request))
        val sending = PortalWorkerMachine.evaluate(dispatch.state, PortalWorkerEvent.DispatchSucceeded(PortalPayload(ByteArray(0))))

        val succeeded = PortalWorkerMachine.evaluate(sending.state, PortalWorkerEvent.ReplySucceeded)
        val failed = PortalWorkerMachine.evaluate(sending.state, PortalWorkerEvent.ReplyFailed("partial write"))

        assertEquals(PortalWorkerState.AwaitingRequest, succeeded.state)
        assertEquals(PortalWorkerState.Stopped, failed.state)
        assertInstanceOf(PortalWorkerTransition.Close::class.java, failed)
    }

    @Test
    fun `non-request frame is a protocol violation and peer closure stops the worker`() {
        val response = PortalFrame(PortalKind.Response, 5, PortalMethod.Echo, ByteArray(0), 0)
        val dropped = PortalWorkerMachine.evaluate(PortalWorkerState.AwaitingRequest, PortalWorkerEvent.FrameReceived(response))
        val stopped = PortalWorkerMachine.evaluate(PortalWorkerState.AwaitingRequest, PortalWorkerEvent.PeerClosed)

        assertTrue(dropped is PortalWorkerTransition.ProtocolViolation)
        assertTrue(dropped.state is PortalWorkerState.Stopped)
        assertTrue(stopped.state is PortalWorkerState.Stopped)
    }
}
