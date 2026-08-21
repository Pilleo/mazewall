package io.mazewall.ffi.networking

import io.mazewall.core.FileDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeccompConnectionMachineTest {

    private val socket = FileDescriptor.unixSocket(10)
    private val listener = FileDescriptor.seccompNotif(20)
    private val accepted = SeccompConnection.Accepted(socket)
    private val attached = accepted.attachFd(listener)
    private val active = attached.handshakeComplete()

    @Test
    fun `accepted plus listener becomes fd-attached`() {
        val t = SeccompConnectionMachine.evaluate(
            accepted,
            SeccompConnectionEvent.ListenerReceived(listener),
        )
        assertTrue(t.connection is SeccompConnection.FdAttached)
        assertEquals(listener, t.connection!!.listenerFd)
        assertTrue(t.effects.any { it is SeccompConnectionEffect.RegisterListener })
    }

    @Test
    fun `accepted poll idle stays accepted`() {
        val t = SeccompConnectionMachine.evaluate(accepted, SeccompConnectionEvent.PollIdle)
        assertEquals(accepted, t.connection)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `accepted recv failure ends the connection`() {
        val t = SeccompConnectionMachine.evaluate(accepted, SeccompConnectionEvent.RecvFailed)
        assertNull(t.connection)
    }

    @Test
    fun `fd-attached ack success becomes active`() {
        val t = SeccompConnectionMachine.evaluate(attached, SeccompConnectionEvent.AckSucceeded)
        assertTrue(t.connection is SeccompConnection.Active)
        assertTrue(t.effects.any { it is SeccompConnectionEffect.LogAck })
    }

    @Test
    fun `fd-attached ack failure ends the connection`() {
        val t = SeccompConnectionMachine.evaluate(attached, SeccompConnectionEvent.AckFailed)
        assertNull(t.connection)
    }

    @Test
    fun `active session finished ends the connection`() {
        val t = SeccompConnectionMachine.evaluate(active, SeccompConnectionEvent.SessionFinished)
        assertNull(t.connection)
        assertTrue(t.effects.any { it is SeccompConnectionEffect.RunSession })
    }

    @Test
    fun `ack on accepted is ignored`() {
        val t = SeccompConnectionMachine.evaluate(accepted, SeccompConnectionEvent.AckSucceeded)
        assertEquals(accepted, t.connection)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `listener received on active is ignored`() {
        val t = SeccompConnectionMachine.evaluate(
            active,
            SeccompConnectionEvent.ListenerReceived(listener),
        )
        assertEquals(active, t.connection)
        assertTrue(t.effects.isEmpty())
    }
}
