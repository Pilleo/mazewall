package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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
    fun `rejects a mismatched response id and accepts a remote error`() {
        val request = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, ByteArray(0), 0)
        val waiting = PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.RequestSent(request))

        val mismatch = PortalBrokerCallMachine.evaluate(
            waiting.state,
            PortalBrokerCallEvent.ReplyReceived(PortalFrame(PortalKind.Response, 8, PortalMethod.Echo, ByteArray(0), 0)),
        )
        val errorPayload = PortalRemoteFailureCodec.encode(PortalRemoteFailure("fixture.Invalid", "no"))
        val remoteError = PortalBrokerCallMachine.evaluate(
            waiting.state,
            PortalBrokerCallEvent.ReplyReceived(
                PortalFrame(
                    PortalKind.Error,
                    7,
                    PortalMethod.Echo,
                    errorPayload,
                    0,
                ),
            ),
        )

        assertEquals(PortalBrokerCallEffect.ProtocolViolation("request id mismatch"), mismatch.effect)
        assertEquals(PortalBrokerCallEffect.RemoteError(PortalPayload(errorPayload)), remoteError.effect)
    }

    @Test
    fun `rejects a response for a different method`() {
        val request = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, ByteArray(0), 0)
        val waiting = PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.RequestSent(request))

        val mismatch = PortalBrokerCallMachine.evaluate(
            waiting.state,
            PortalBrokerCallEvent.ReplyReceived(PortalFrame(PortalKind.Response, 7, PortalMethod.Checksum, ByteArray(0), 0)),
        )

        assertEquals(PortalBrokerCallEffect.ProtocolViolation("method mismatch"), mismatch.effect)
    }

    @Test
    fun `reply without a request is a protocol violation`() {
        val reply = PortalFrame(PortalKind.Response, 7, PortalMethod.Echo, ByteArray(0), 0)

        val violation = PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.ReplyReceived(reply))

        assertTrue(violation.state is PortalBrokerCallState.Failed)
        assertEquals(PortalBrokerCallEffect.ProtocolViolation("reply received before request"), violation.effect)
    }

    @Test
    fun `transport failure requires worker recycle`() {
        val failed = PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.TransportFailed)

        assertTrue(failed.state is PortalBrokerCallState.Failed)
        assertEquals(PortalBrokerCallEffect.RecycleWorker("portal RPC failed"), failed.effect)
    }

    @Test
    fun `post terminal events are explicit observations`() {
        val request = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, ByteArray(0), 0)
        val completed = PortalBrokerCallMachine.evaluate(
            PortalBrokerCallState.AwaitingReply(request),
            PortalBrokerCallEvent.ReplyReceived(PortalFrame(PortalKind.Response, 7, PortalMethod.Echo, ByteArray(0), 0)),
        )

        val observed = PortalBrokerCallMachine.evaluate(completed.state, PortalBrokerCallEvent.TransportFailed)

        assertEquals(
            PortalBrokerCallEffect.PostTerminalObservation(PortalBrokerCallState.Completed, PortalBrokerCallEvent.TransportFailed),
            observed.effect,
        )
    }
}
