package io.mazewall.portal.worker

import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalKind
import io.mazewall.portal.PortalMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortalWorkerMachineTest {
    @Test
    fun `request dispatches then replies with the matching response`() {
        val request = PortalFrame(PortalKind.Request, 5, PortalMethod.Echo, byteArrayOf(1), 0)
        val dispatch = PortalWorkerMachine.evaluate(PortalWorkerState.AwaitingRequest, PortalWorkerEvent.FrameReceived(request))
        val reply = PortalWorkerMachine.evaluate(dispatch.state, PortalWorkerEvent.DispatchSucceeded(byteArrayOf(2)))

        assertTrue(dispatch.effect is PortalWorkerEffect.Dispatch)
        val sent = reply.effect as PortalWorkerEffect.Send
        assertEquals(PortalKind.Response, sent.frame.kind)
        assertEquals(5, sent.frame.requestId)
        assertEquals(PortalMethod.Echo, sent.frame.method)
        assertTrue(sent.frame.payload.contentEquals(byteArrayOf(2)))
    }

    @Test
    fun `non-request frame is dropped and peer closure stops the worker`() {
        val response = PortalFrame(PortalKind.Response, 5, PortalMethod.Echo, ByteArray(0), 0)
        val dropped = PortalWorkerMachine.evaluate(PortalWorkerState.AwaitingRequest, PortalWorkerEvent.FrameReceived(response))
        val stopped = PortalWorkerMachine.evaluate(PortalWorkerState.AwaitingRequest, PortalWorkerEvent.PeerClosed)

        assertEquals(PortalWorkerEffect.Drop, dropped.effect)
        assertTrue(stopped.state is PortalWorkerState.Stopped)
    }
}
