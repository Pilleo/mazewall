package io.mazewall.ffi.networking

import io.mazewall.core.FileDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.reflect.KClass

internal class SeccompConnectionMachineTest {

    companion object {
        private val socket = FileDescriptor.unixSocket(10)
        private val listener = FileDescriptor.seccompNotif(20)
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
        fun connectionTransitions(): Stream<ConnectionTestCase> = Stream.of(
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

    @Test
    fun `compile-time exhaustive coverage of connection and event variants`() {
        val connections: List<SeccompConnection> = listOf(accepted, attached, active)
        val events: List<SeccompConnectionEvent> = listOf(
            SeccompConnectionEvent.ListenerReceived(listener),
            SeccompConnectionEvent.PollIdle,
            SeccompConnectionEvent.PollFailed,
            SeccompConnectionEvent.RecvFailed,
            SeccompConnectionEvent.AckSucceeded,
            SeccompConnectionEvent.AckFailed,
            SeccompConnectionEvent.SessionFinished,
        )

        for (conn in connections) {
            // Compile-time exhaustive connection check
            when (conn) {
                is SeccompConnection.Accepted -> Unit
                is SeccompConnection.FdAttached -> Unit
                is SeccompConnection.Active -> Unit
            }
            for (event in events) {
                // Compile-time exhaustive event check
                when (event) {
                    is SeccompConnectionEvent.ListenerReceived -> Unit
                    is SeccompConnectionEvent.PollIdle -> Unit
                    is SeccompConnectionEvent.PollFailed -> Unit
                    is SeccompConnectionEvent.RecvFailed -> Unit
                    is SeccompConnectionEvent.AckSucceeded -> Unit
                    is SeccompConnectionEvent.AckFailed -> Unit
                    is SeccompConnectionEvent.SessionFinished -> Unit
                }
                // Total function verification
                SeccompConnectionMachine.evaluate(conn, event)
            }
        }
    }
}
