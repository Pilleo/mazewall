package io.mazewall.profiler.engine

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.reflect.KClass

internal class ProfilerSessionMachineTest {
    companion object {
        private val socket = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(3)
        private val listener = TestFileDescriptors
            .replace<FileDescriptorRole.SeccompNotif>(4)
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

        data class ProtocolViolationCase(
            val initialState: ProfilerState,
            val event: ProfilerSessionEvent,
        ) {
            override fun toString(): String = "${initialState::class.simpleName} rejects ${event::class.simpleName}"
        }

        @JvmStatic
        fun sessionTransitions(): Stream<SessionTestCase> =
            Stream.of(
            SessionTestCase(
                name = "active notification becomes notified",
                initialState = active,
                event = ProfilerSessionEvent.NotificationReceived(9L, event),
                expectedStateType = ProfilerState.Notified::class,
            ),
            SessionTestCase(
                name = "active noise notification is explicitly passed through",
                initialState = active,
                event = ProfilerSessionEvent.NoisePathBypassed,
                expectedStateType = ProfilerState.ActiveSession::class,
                expectedPassThrough = true,
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
                name = "handshake failure from waiting terminates session",
                initialState = waiting,
                event = ProfilerSessionEvent.HandshakeFailed,
                expectedStateType = ProfilerState.Terminated::class,
                expectedTerminate = true,
            ),
            SessionTestCase(
                name = "handshake failure from notified terminates session",
                initialState = notified,
                event = ProfilerSessionEvent.HandshakeFailed,
                expectedStateType = ProfilerState.Terminated::class,
                expectedTerminate = true,
            ),
            SessionTestCase(
                name = "transport failure terminates an active session",
                initialState = active,
                event = ProfilerSessionEvent.TransportFailed,
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

        @JvmStatic
        fun illegalSessionEvents(): Stream<ProtocolViolationCase> {
            val states = listOf(
                ProfilerState.Connected(socket),
                ProfilerState.HandshakeAck(socket, listener),
                active,
                notified,
                waiting,
                ProfilerState.PassThrough(socket, listener),
            )
            val events = listOf(
                ProfilerSessionEvent.NotificationReceived(9L, event),
                ProfilerSessionEvent.EventDelivered,
                ProfilerSessionEvent.AckSucceeded,
                ProfilerSessionEvent.HandshakeFailed,
                ProfilerSessionEvent.PassedThrough,
                ProfilerSessionEvent.NoisePathBypassed,
                ProfilerSessionEvent.PostTerminalObserved,
            )
            val handled = setOf(
                ProfilerState.ActiveSession::class to ProfilerSessionEvent.NotificationReceived::class,
                ProfilerState.ActiveSession::class to ProfilerSessionEvent.NoisePathBypassed::class,
                ProfilerState.Notified::class to ProfilerSessionEvent.EventDelivered::class,
                ProfilerState.Notified::class to ProfilerSessionEvent.HandshakeFailed::class,
                ProfilerState.WaitingForAck::class to ProfilerSessionEvent.AckSucceeded::class,
                ProfilerState.WaitingForAck::class to ProfilerSessionEvent.HandshakeFailed::class,
                ProfilerState.WaitingForAck::class to ProfilerSessionEvent.PassedThrough::class,
            )
            return states
                .flatMap { state ->
                events
                    .filter { event -> (state::class to event::class) !in handled }
                    .map { event -> ProtocolViolationCase(state, event) }
            }.stream()
        }

        @JvmStatic
        fun terminalStates(): Stream<ProfilerState.Terminated> = Stream.of(ProfilerState.Terminated(socket, listener))
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalSessionEvents")
    fun `verify non-applicable profiler events terminate as protocol violations`(testCase: ProtocolViolationCase) {
        val transition = ProfilerSessionMachine.evaluate(testCase.initialState, testCase.event)
        assertTrue(transition.state is ProfilerState.Terminated)
        assertTrue(transition.terminate)
        assertTrue(transition.protocolViolation)
    }

    @ParameterizedTest
    @MethodSource("terminalStates")
    fun `post terminal observation is explicitly benign`(state: ProfilerState.Terminated) {
        val transition = ProfilerSessionMachine.evaluate(state, ProfilerSessionEvent.PostTerminalObserved)
        assertEquals(state, transition.state)
        assertEquals(false, transition.protocolViolation)
    }
}
