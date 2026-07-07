package io.mazewall.profiler.internal

import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TraceListenerStateTest {

    @Test
    fun `test AwaitingEvent`() {
        val state = TraceListenerState.AwaitingEvent
        assertEquals(TraceListenerState.AwaitingEvent, state)
        assertTrue(state.toString().contains("AwaitingEvent"))
    }

    @Test
    fun `test ReadingHeader`() {
        val state = TraceListenerState.ReadingHeader(123)
        assertEquals(123, state.pid)
        assertTrue(state.toString().contains("ReadingHeader"))
        assertTrue(state.toString().contains("123"))
    }

    @Test
    fun `test ReadingSyscall`() {
        val state = TraceListenerState.ReadingSyscall(123, 10)
        assertEquals(123, state.pid)
        assertEquals(10, state.nameLen)
        assertTrue(state.toString().contains("ReadingSyscall"))
    }

    @Test
    fun `test ReadingArguments`() {
        val state = TraceListenerState.ReadingArguments(123, "openat", 4)
        assertEquals(123, state.pid)
        assertEquals("openat", state.name)
        assertEquals(4, state.argsCount)
        assertTrue(state.toString().contains("ReadingArguments"))
    }

    @Test
    fun `test ProcessingEvent`() {
        val event = TraceEvent.invoke(
            tidValue = 123,
            syscallName = "OPENAT",
            args = longArrayOf(0, 0, 0, 0),
            paths = listOf("/tmp/test"),
            stackTrace = null
        )
        val state = TraceListenerState.ProcessingEvent(event)
        assertEquals(event, state.event)
        assertTrue(state.toString().contains("ProcessingEvent"))
    }

    @Test
    fun `test Disconnected`() {
        val state = TraceListenerState.Disconnected
        assertEquals(TraceListenerState.Disconnected, state)
        assertTrue(state.toString().contains("Disconnected"))
    }
}
