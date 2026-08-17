package io.mazewall.profiler.engine

import io.mazewall.core.FileDescriptor
import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerSessionMachineTest {

    private val socket = FileDescriptor.unixSocket(3)
    private val listener = FileDescriptor.seccompNotif(4)
    private val active = ProfilerState.ActiveSession(socket, listener)
    private val event = SyscallEvent<SyscallEventState.Resolved>(
        tid = Tid(1),
        syscallName = "openat",
        args = arrayListOf(0L),
        paths = emptyList(),
    )

    @Test
    fun `active notification becomes notified then waiting`() {
        val notified = ProfilerSessionMachine.evaluate(
            active,
            ProfilerSessionEvent.NotificationReceived(9L, event),
        )
        assertTrue(notified.state is ProfilerState.Notified)
        val waiting = ProfilerSessionMachine.evaluate(
            notified.state,
            ProfilerSessionEvent.EventDelivered,
        )
        assertTrue(waiting.state is ProfilerState.WaitingForAck)
    }

    @Test
    fun `ack returns to active session`() {
        val waiting = ProfilerState.WaitingForAck(socket, listener, 9L)
        val next = ProfilerSessionMachine.evaluate(waiting, ProfilerSessionEvent.AckSucceeded)
        assertEquals(active, next.state)
    }

    @Test
    fun `ack before notify is ignored`() {
        val next = ProfilerSessionMachine.evaluate(active, ProfilerSessionEvent.AckSucceeded)
        assertEquals(active, next.state)
    }

    @Test
    fun `handshake failure from waiting terminates`() {
        val waiting = ProfilerState.WaitingForAck(socket, listener, 9L)
        val next = ProfilerSessionMachine.evaluate(waiting, ProfilerSessionEvent.HandshakeFailed)
        assertTrue(next.state is ProfilerState.Terminated)
        assertTrue(next.terminate)
    }
}
