package io.mazewall.profiler.engine

import io.mazewall.core.FileDescriptor
import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.reflect.KClass

internal class ProfilerSessionMachineTest {

    companion object {
        private val socket = FileDescriptor.unixSocket(3)
        private val listener = FileDescriptor.seccompNotif(4)
        private val active = ProfilerState.ActiveSession(socket, listener)
        private val event = SyscallEvent<SyscallEventState.Resolved>(
            tid = Tid(1),
            syscallName = "openat",
            args = arrayListOf(0L),
            paths = emptyList(),
        )
        private val notified = ProfilerState.Notified(socket, listener, 9L, event)
        private val waiting = ProfilerState.WaitingForAck(socket, listener, 9L)

        data class SessionTestCase(
            val name: String,
            val initialState: ProfilerState,
            val event: ProfilerSessionEvent,
            val expectedStateType: KClass<out ProfilerState>,
            val expectedTerminate: Boolean = false,
            val expectedPassThrough: Boolean = false,
        ) {
            override fun toString(): String = name
        }

        @JvmStatic
        fun sessionTransitions(): Stream<SessionTestCase> = Stream.of(
            SessionTestCase(
                name = "active notification becomes notified",
                initialState = active,
                event = ProfilerSessionEvent.NotificationReceived(9L, event),
                expectedStateType = ProfilerState.Notified::class,
            ),
            SessionTestCase(
                name = "notified event delivered becomes waiting for ack",
                initialState = notified,
                event = ProfilerSessionEvent.EventDelivered,
                expectedStateType = ProfilerState.WaitingForAck::class,
            ),
            SessionTestCase(
                name = "ack from waiting returns to active session",
                initialState = waiting,
                event = ProfilerSessionEvent.AckSucceeded,
                expectedStateType = ProfilerState.ActiveSession::class,
            ),
            SessionTestCase(
                name = "ack before notify is ignored and stays active",
                initialState = active,
                event = ProfilerSessionEvent.AckSucceeded,
                expectedStateType = ProfilerState.ActiveSession::class,
            ),
            SessionTestCase(
                name = "handshake failure from waiting terminates session",
                initialState = waiting,
                event = ProfilerSessionEvent.HandshakeFailed,
                expectedStateType = ProfilerState.Terminated::class,
                expectedTerminate = true,
            ),
            SessionTestCase(
                name = "passed through event transitions waiting back to active with passThrough flag",
                initialState = waiting,
                event = ProfilerSessionEvent.PassedThrough,
                expectedStateType = ProfilerState.ActiveSession::class,
                expectedPassThrough = true,
            ),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionTransitions")
    fun `verify profiler session transition matrix`(testCase: SessionTestCase) {
        val transition = ProfilerSessionMachine.evaluate(testCase.initialState, testCase.event)
        assertTrue(
            testCase.expectedStateType.isInstance(transition.state),
            "Expected ${testCase.expectedStateType.simpleName} but got ${transition.state::class.simpleName}",
        )
        assertEquals(testCase.expectedTerminate, transition.terminate)
        assertEquals(testCase.expectedPassThrough, transition.passThrough)
    }

    @Test
    fun `compile-time exhaustive coverage of profiler state and event variants`() {
        val states: List<ProfilerState> = listOf(
            ProfilerState.Connected(socket),
            ProfilerState.HandshakeAck(socket, listener),
            active,
            notified,
            waiting,
            ProfilerState.PassThrough(socket, listener),
            ProfilerState.Terminated(socket, listener),
        )
        val events: List<ProfilerSessionEvent> = listOf(
            ProfilerSessionEvent.NotificationReceived(9L, event),
            ProfilerSessionEvent.EventDelivered,
            ProfilerSessionEvent.AckSucceeded,
            ProfilerSessionEvent.HandshakeFailed,
            ProfilerSessionEvent.PassedThrough,
        )

        for (state in states) {
            when (state) {
                is ProfilerState.Connected -> Unit
                is ProfilerState.HandshakeAck -> Unit
                is ProfilerState.ActiveSession -> Unit
                is ProfilerState.Notified -> Unit
                is ProfilerState.WaitingForAck -> Unit
                is ProfilerState.PassThrough -> Unit
                is ProfilerState.Terminated -> Unit
            }
            for (ev in events) {
                when (ev) {
                    is ProfilerSessionEvent.NotificationReceived -> Unit
                    is ProfilerSessionEvent.EventDelivered -> Unit
                    is ProfilerSessionEvent.AckSucceeded -> Unit
                    is ProfilerSessionEvent.HandshakeFailed -> Unit
                    is ProfilerSessionEvent.PassedThrough -> Unit
                }
                val transition = ProfilerSessionMachine.evaluate(state, ev)
                assertEquals(false, transition.state == null)
            }
        }
    }
}
