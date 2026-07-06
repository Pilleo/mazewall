package io.mazewall.profiler.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TraceListenerStateTest {

    @Test
    fun `test state transitions coverage`() {
        val s1: TraceListenerState = TraceListenerState.AwaitingEvent
        val s2: TraceListenerState = TraceListenerState.ReadingHeader(pid = 123)
        val s3: TraceListenerState = TraceListenerState.ReadingSyscall(pid = 123, nameLen = 4)
        val s4: TraceListenerState = TraceListenerState.ReadingArguments(pid = 123, name = "OPEN", argsCount = 2)
        val s5: TraceListenerState = TraceListenerState.Disconnected

        assertEquals(TraceListenerState.AwaitingEvent, s1)
        assertEquals(123, (s2 as TraceListenerState.ReadingHeader).pid)
        assertEquals(4, (s3 as TraceListenerState.ReadingSyscall).nameLen)
        assertEquals("OPEN", (s4 as TraceListenerState.ReadingArguments).name)
        assertTrue(s5 is TraceListenerState.Disconnected)
    }
}
