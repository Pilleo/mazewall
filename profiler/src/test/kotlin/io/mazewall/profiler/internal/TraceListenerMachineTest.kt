package io.mazewall.profiler.internal

import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class TraceListenerMachineTest {
    @Test
    fun `every listener state and event combination is classified`() {
        val trace = TraceEvent.invoke(42, "OPENAT", longArrayOf(), emptyList(), null)
        val states = listOf(
            TraceListenerState.Disconnected,
            TraceListenerState.AwaitingEvent,
            TraceListenerState.ReadingHeader(42),
            TraceListenerState.ReadingSyscall(42, 6),
            TraceListenerState.ReadingArguments(42, "OPENAT", 0),
            TraceListenerState.ProcessingEvent(trace),
            TraceListenerState.HandshakeRejected,
            TraceListenerState.ProtocolViolation("invalid"),
        )
        val events = listOf(
            TraceListenerEvent.HandshakeReceived,
            TraceListenerEvent.HandshakeRejected,
            TraceListenerEvent.AwaitEvent,
            TraceListenerEvent.HeaderRead(42),
            TraceListenerEvent.SyscallRead(42, 6),
            TraceListenerEvent.ArgumentsRead(42, "OPENAT", 0),
            TraceListenerEvent.EventRead(trace),
            TraceListenerEvent.SocketClosed,
        )
        val violation = TraceListenerState.ProtocolViolation::class
        val awaiting = TraceListenerState.AwaitingEvent::class
        val closed = TraceListenerState.Disconnected::class
        val rejected = TraceListenerState.HandshakeRejected::class
        val expected = listOf(
            listOf(awaiting, rejected, violation, violation, violation, violation, violation, closed),
            listOf(violation, violation, awaiting, TraceListenerState.ReadingHeader::class, violation, violation, violation, closed),
            listOf(violation, violation, violation, violation, TraceListenerState.ReadingSyscall::class, violation, violation, closed),
            listOf(violation, violation, violation, violation, violation, TraceListenerState.ReadingArguments::class, violation, closed),
            listOf(violation, violation, violation, violation, violation, violation, TraceListenerState.ProcessingEvent::class, closed),
            listOf(violation, violation, awaiting, violation, violation, violation, violation, closed),
            List(events.size) { rejected },
            List(events.size) { violation },
        )
        states.forEachIndexed { row, state ->
            events.forEachIndexed { column, event ->
                assertEquals(expected[row][column], TraceListenerMachine.evaluate(state, event)::class, "$state / $event")
            }
        }
    }

    @Test
    fun `socket lifecycle transitions are evaluated without IO`() {
        val event = TraceEvent.invoke(42, "OPENAT", longArrayOf(), emptyList(), null)

        assertEquals(
            TraceListenerState.AwaitingEvent,
            TraceListenerMachine.evaluate(TraceListenerState.Disconnected, TraceListenerEvent.HandshakeReceived),
        )
        assertEquals(
            TraceListenerState.ReadingHeader(42),
            TraceListenerMachine.evaluate(TraceListenerState.AwaitingEvent, TraceListenerEvent.HeaderRead(42)),
        )
        assertEquals(
            TraceListenerState.ReadingSyscall(42, 6),
            TraceListenerMachine.evaluate(
                TraceListenerState.ReadingHeader(42),
                TraceListenerEvent.SyscallRead(42, 6),
            ),
        )
        assertEquals(
            TraceListenerState.ProcessingEvent(event),
            TraceListenerMachine.evaluate(TraceListenerState.ReadingArguments(42, "OPENAT", 0), TraceListenerEvent.EventRead(event)),
        )
        assertEquals(
            TraceListenerState.Disconnected,
            TraceListenerMachine.evaluate(TraceListenerState.ProcessingEvent(event), TraceListenerEvent.SocketClosed),
        )
    }

    @Test
    fun `out of order socket events are explicit protocol violations`() {
        assertInstanceOf(
            TraceListenerState.ProtocolViolation::class.java,
            TraceListenerMachine.evaluate(TraceListenerState.Disconnected, TraceListenerEvent.HeaderRead(42)),
        )
    }

    @Test
    fun `invalid wire tid terminates parsing before the event body`() {
        for (pid in listOf(0, -1, Int.MIN_VALUE)) {
            val rejected = assertInstanceOf(
                TraceListenerState.ProtocolViolation::class.java,
                TraceListenerMachine.evaluate(TraceListenerState.AwaitingEvent, TraceListenerEvent.HeaderRead(pid)),
            )
            assertEquals(rejected, TraceListenerMachine.evaluate(rejected, TraceListenerEvent.SocketClosed))
            assertEquals(rejected, TraceListenerMachine.evaluate(rejected, TraceListenerEvent.AwaitEvent))
        }
    }

    @Test
    fun `rejected handshake cannot resume event processing`() {
        assertEquals(TraceListenerState.HandshakeRejected, TraceListenerMachine.evaluate(TraceListenerState.HandshakeRejected, TraceListenerEvent.AwaitEvent))
    }

    @Test
    fun `rejected handshake remains terminal when the socket closes`() {
        val rejected = TraceListenerMachine.evaluate(
            TraceListenerState.Disconnected,
            TraceListenerEvent.HandshakeRejected,
        )

        assertEquals(TraceListenerState.HandshakeRejected, rejected)
        assertEquals(
            TraceListenerState.HandshakeRejected,
            TraceListenerMachine.evaluate(rejected, TraceListenerEvent.SocketClosed),
        )
    }
}
