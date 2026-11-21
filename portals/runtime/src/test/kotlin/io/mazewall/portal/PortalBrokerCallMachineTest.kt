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
    fun `accepts a response for the active request and releases only after reply validation`() {
        val request = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, byteArrayOf(1), 0)
        val response = PortalFrame(PortalKind.Response, 7, PortalMethod.Echo, byteArrayOf(2), 0)

        val waiting = PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.RequestSent(request))
        val validating = PortalBrokerCallMachine.evaluate(waiting.state, PortalBrokerCallEvent.ReplyReceived(response))

        assertEquals(PortalBrokerCallState.ValidatingReply(request), validating.state)
        assertEquals(PortalBrokerCallEffect.ValidatePayload(response.payload), validating.effect)

        val released = PortalBrokerCallMachine.evaluate(validating.state, PortalBrokerCallEvent.ReplyValidated)
        assertEquals(PortalBrokerCallState.Completed, released.state)
        assertEquals(PortalBrokerCallEffect.ReleaseWorker, released.effect)
    }

    @Test
    fun `malformed reply validation transitions from ValidatingReply to Failed with ProtocolViolation`() {
        val request = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, byteArrayOf(1), 0)
        val response = PortalFrame(PortalKind.Response, 7, PortalMethod.Echo, byteArrayOf(2), 0)
        val validating = PortalBrokerCallMachine.evaluate(
            PortalBrokerCallState.AwaitingReply(request),
            PortalBrokerCallEvent.ReplyReceived(response),
        )

        val failed = PortalBrokerCallMachine.evaluate(validating.state, PortalBrokerCallEvent.ReplyValidationFailed("trailing bytes"))

        assertEquals(PortalBrokerCallState.Failed, failed.state)
        assertEquals(PortalBrokerCallEffect.ProtocolViolation("trailing bytes"), failed.effect)
        val postTerminal = PortalBrokerCallMachine.evaluate(failed.state, PortalBrokerCallEvent.ReplyValidated)
        assertEquals(
            PortalBrokerCallEffect.PostTerminalObservation(PortalBrokerCallState.Failed, PortalBrokerCallEvent.ReplyValidated),
            postTerminal.effect,
        )
    }

    @Test
    fun `reply validation events out of order and transport failure during validation fail closed`() {
        val request = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, ByteArray(0), 0)
        val response = PortalFrame(PortalKind.Response, 7, PortalMethod.Echo, ByteArray(0), 0)

        assertEquals(
            PortalBrokerCallEffect.ProtocolViolation("reply validated before request"),
            PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.ReplyValidated).effect,
        )
        assertEquals(
            PortalBrokerCallEffect.ProtocolViolation("bad"),
            PortalBrokerCallMachine.evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.ReplyValidationFailed("bad")).effect,
        )
        assertEquals(
            PortalBrokerCallEffect.ProtocolViolation("reply validated before reply received"),
            PortalBrokerCallMachine.evaluate(PortalBrokerCallState.AwaitingReply(request), PortalBrokerCallEvent.ReplyValidated).effect,
        )
        assertEquals(
            PortalBrokerCallEffect.ProtocolViolation("bad"),
            PortalBrokerCallMachine.evaluate(PortalBrokerCallState.AwaitingReply(request), PortalBrokerCallEvent.ReplyValidationFailed("bad")).effect,
        )
        assertEquals(
            PortalBrokerCallEffect.ProtocolViolation("request sent while validating reply"),
            PortalBrokerCallMachine.evaluate(PortalBrokerCallState.ValidatingReply(request), PortalBrokerCallEvent.RequestSent(request)).effect,
        )
        assertEquals(
            PortalBrokerCallEffect.ProtocolViolation("duplicate reply while validating reply"),
            PortalBrokerCallMachine.evaluate(PortalBrokerCallState.ValidatingReply(request), PortalBrokerCallEvent.ReplyReceived(response)).effect,
        )
        val transportFailed = PortalBrokerCallMachine.evaluate(
            PortalBrokerCallState.ValidatingReply(request),
            PortalBrokerCallEvent.TransportFailed,
        )
        assertEquals(PortalBrokerCallState.Failed, transportFailed.state)
        assertEquals(PortalBrokerCallEffect.RecycleWorker("portal RPC failed"), transportFailed.effect)
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
        val validating = PortalBrokerCallMachine.evaluate(
            PortalBrokerCallState.AwaitingReply(request),
            PortalBrokerCallEvent.ReplyReceived(PortalFrame(PortalKind.Response, 7, PortalMethod.Echo, ByteArray(0), 0)),
        )
        val completed = PortalBrokerCallMachine.evaluate(validating.state, PortalBrokerCallEvent.ReplyValidated)

        val observed = PortalBrokerCallMachine.evaluate(completed.state, PortalBrokerCallEvent.TransportFailed)

        assertEquals(
            PortalBrokerCallEffect.PostTerminalObservation(PortalBrokerCallState.Completed, PortalBrokerCallEvent.TransportFailed),
            observed.effect,
        )
    }
}
