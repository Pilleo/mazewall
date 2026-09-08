package io.mazewall.profiler.internal

import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TraceListenerMachineTest {
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
    fun `out of order socket events preserve the current state`() {
        assertEquals(
            TraceListenerState.Disconnected,
            TraceListenerMachine.evaluate(TraceListenerState.Disconnected, TraceListenerEvent.HeaderRead(42)),
        )
    }
}
