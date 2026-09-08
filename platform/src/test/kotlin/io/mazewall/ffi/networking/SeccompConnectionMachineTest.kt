package io.mazewall.ffi.networking

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.reflect.KClass

internal class SeccompConnectionMachineTest {
    companion object {
        private val socket = FileDescriptor.replace<FileDescriptorRole.UnixSocket>(10)
        private val listener = FileDescriptor.replace<FileDescriptorRole.SeccompNotif>(20)
        private val accepted = SeccompConnection.Accepted(socket)
        private val attached = accepted.attachFd(listener)
        private val active = attached.handshakeComplete()

        data class ConnectionTestCase(
            val name: String,
            val initialConnection: SeccompConnection,
            val event: SeccompConnectionEvent,
            val expectedConnection: SeccompConnection?,
            val expectedEffectTypes: List<KClass<out SeccompConnectionEffect>> = emptyList(),
            val connectionValidator: (SeccompConnection?) -> Unit = {},
        ) {
            override fun toString(): String = name
        }

        @JvmStatic
        fun connectionTransitions(): Stream<ConnectionTestCase> =
            Stream.of(
            ConnectionTestCase(
                name = "accepted plus listener becomes fd-attached",
                initialConnection = accepted,
                event = SeccompConnectionEvent.ListenerReceived(listener),
                expectedConnection = attached,
                expectedEffectTypes = listOf(SeccompConnectionEffect.RegisterListener::class),
                connectionValidator = { conn ->
                    assertTrue(conn is SeccompConnection.FdAttached)
                    assertEquals(listener, (conn as SeccompConnection.FdAttached).listenerFd)
                },
            ),
            ConnectionTestCase(
                name = "accepted poll idle stays accepted",
                initialConnection = accepted,
                event = SeccompConnectionEvent.PollIdle,
                expectedConnection = accepted,
                expectedEffectTypes = emptyList(),
            ),
            ConnectionTestCase(
                name = "accepted poll failed ends the connection",
                initialConnection = accepted,
                event = SeccompConnectionEvent.PollFailed,
                expectedConnection = null,
                expectedEffectTypes = emptyList(),
            ),
            ConnectionTestCase(
                name = "accepted recv failure ends the connection",
                initialConnection = accepted,
                event = SeccompConnectionEvent.RecvFailed,
                expectedConnection = null,
                expectedEffectTypes = emptyList(),
            ),
            ConnectionTestCase(
                name = "fd-attached ack success becomes active",
                initialConnection = attached,
                event = SeccompConnectionEvent.AckSucceeded,
                expectedConnection = active,
                expectedEffectTypes = listOf(SeccompConnectionEffect.LogAck::class),
                connectionValidator = { conn ->
                    assertTrue(conn is SeccompConnection.Active)
                },
            ),
            ConnectionTestCase(
                name = "fd-attached ack failure ends the connection",
                initialConnection = attached,
                event = SeccompConnectionEvent.AckFailed,
                expectedConnection = null,
                expectedEffectTypes = emptyList(),
            ),
            ConnectionTestCase(
                name = "active session finished ends the connection",
                initialConnection = active,
                event = SeccompConnectionEvent.SessionFinished,
                expectedConnection = null,
                expectedEffectTypes = listOf(SeccompConnectionEffect.RunSession::class),
            ),
            ConnectionTestCase(
                name = "ack on accepted is ignored",
                initialConnection = accepted,
                event = SeccompConnectionEvent.AckSucceeded,
                expectedConnection = accepted,
                expectedEffectTypes = emptyList(),
            ),
            ConnectionTestCase(
                name = "listener received on active is ignored",
                initialConnection = active,
                event = SeccompConnectionEvent.ListenerReceived(listener),
                expectedConnection = active,
                expectedEffectTypes = emptyList(),
            ),
            ConnectionTestCase(
                name = "ack failure before listener attachment is ignored",
                initialConnection = accepted,
                event = SeccompConnectionEvent.AckFailed,
                expectedConnection = accepted,
            ),
            ConnectionTestCase(
                name = "session completion before listener attachment is ignored",
                initialConnection = accepted,
                event = SeccompConnectionEvent.SessionFinished,
                expectedConnection = accepted,
            ),
            ConnectionTestCase(
                name = "second listener received after attachment is ignored",
                initialConnection = attached,
                event = SeccompConnectionEvent.ListenerReceived(listener),
                expectedConnection = attached,
            ),
            ConnectionTestCase(
                name = "receive failure after attachment is ignored",
                initialConnection = attached,
                event = SeccompConnectionEvent.RecvFailed,
                expectedConnection = attached,
            ),
            ConnectionTestCase(
                name = "poll idle after attachment is ignored",
                initialConnection = attached,
                event = SeccompConnectionEvent.PollIdle,
                expectedConnection = attached,
            ),
            ConnectionTestCase(
                name = "poll failure after attachment is ignored",
                initialConnection = attached,
                event = SeccompConnectionEvent.PollFailed,
                expectedConnection = attached,
            ),
            ConnectionTestCase(
                name = "session completion before acknowledgement is ignored",
                initialConnection = attached,
                event = SeccompConnectionEvent.SessionFinished,
                expectedConnection = attached,
            ),
            ConnectionTestCase(
                name = "receive failure while active is ignored",
                initialConnection = active,
                event = SeccompConnectionEvent.RecvFailed,
                expectedConnection = active,
            ),
            ConnectionTestCase(
                name = "poll idle while active is ignored",
                initialConnection = active,
                event = SeccompConnectionEvent.PollIdle,
                expectedConnection = active,
            ),
            ConnectionTestCase(
                name = "poll failure while active is ignored",
                initialConnection = active,
                event = SeccompConnectionEvent.PollFailed,
                expectedConnection = active,
            ),
            ConnectionTestCase(
                name = "duplicate acknowledgement while active is ignored",
                initialConnection = active,
                event = SeccompConnectionEvent.AckSucceeded,
                expectedConnection = active,
            ),
            ConnectionTestCase(
                name = "failed acknowledgement while active is ignored",
                initialConnection = active,
                event = SeccompConnectionEvent.AckFailed,
                expectedConnection = active,
            ),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("connectionTransitions")
    fun `verify seccomp connection transition matrix`(testCase: ConnectionTestCase) {
        val t = SeccompConnectionMachine.evaluate(testCase.initialConnection, testCase.event)
        assertEquals(testCase.expectedConnection, t.connection)
        for (expectedEffect in testCase.expectedEffectTypes) {
            assertTrue(
                t.effects.any { expectedEffect.isInstance(it) },
                "Expected effect ${expectedEffect.simpleName} in ${t.effects}",
            )
        }
        if (testCase.expectedEffectTypes.isEmpty()) {
            assertTrue(t.effects.isEmpty(), "Expected no effects but got ${t.effects}")
        }
        testCase.connectionValidator(t.connection)
    }
}
