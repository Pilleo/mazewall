package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortalBrokerCallMachineTest {
    @Test
    fun `accepts a response for the active request`() {
        val request = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, byteArrayOf(1), 0)
        val response = PortalFrame(PortalKind.Response, 7, PortalMethod.Echo, byteArrayOf(2), 0)

        val waiting = PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.RequestSent(request))
        val accepted = PortalBrokerCallMachine.evaluate(waiting.state, PortalBrokerCallEvent.ReplyReceived(response))

        assertTrue(accepted.state is PortalBrokerCallState.Completed)
        assertEquals(PortalBrokerCallEffect.ReturnPayload(response.payload), accepted.effect)
    }

    @Test
    fun `rejects a mismatched response id and a remote error`() {
        val request = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, ByteArray(0), 0)
        val waiting = PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.RequestSent(request))

        val mismatch = PortalBrokerCallMachine.evaluate(
            waiting.state,
            PortalBrokerCallEvent.ReplyReceived(PortalFrame(PortalKind.Response, 8, PortalMethod.Echo, ByteArray(0), 0)),
        )
        val remoteError = PortalBrokerCallMachine.evaluate(
            waiting.state,
            PortalBrokerCallEvent.ReplyReceived(PortalFrame(PortalKind.Error, 7, PortalMethod.Echo, "no".encodeToByteArray(), 0)),
        )

        assertEquals(PortalBrokerCallEffect.RecycleWorker("request id mismatch"), mismatch.effect)
        assertEquals(PortalBrokerCallEffect.RemoteError("no"), remoteError.effect)
    }

    @Test
    fun `transport failure requires worker recycle`() {
        val failed = PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.TransportFailed)

        assertTrue(failed.state is PortalBrokerCallState.Failed)
        assertEquals(PortalBrokerCallEffect.RecycleWorker("portal RPC failed"), failed.effect)
    }
}
