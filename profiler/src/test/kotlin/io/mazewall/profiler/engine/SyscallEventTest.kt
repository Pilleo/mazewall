package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SyscallEventTest {

    @Test
    fun `test resolved extension function transitions state and adds paths`() {
        val rawEvent = SyscallEvent<SyscallEventState.Raw>(
            tid = Tid(100),
            syscallName = "OPENAT",
            args = listOf(1L, 2L, 3L)
        )

        val paths = listOf("/etc/passwd")
        val resolvedEvent = rawEvent.resolved(paths)

        assertEquals(rawEvent.tid, resolvedEvent.tid)
        assertEquals(rawEvent.syscallName, resolvedEvent.syscallName)
        assertEquals(rawEvent.args, resolvedEvent.args)
        assertEquals(paths, resolvedEvent.paths)
    }

    @Test
    fun `test equality and hashcode`() {
        val event1 = SyscallEvent<SyscallEventState.Raw>(
            tid = Tid(100),
            syscallName = "OPENAT",
            args = listOf(1L, 2L)
        )
        val event2 = SyscallEvent<SyscallEventState.Raw>(
            tid = Tid(100),
            syscallName = "OPENAT",
            args = listOf(1L, 2L)
        )
        val event3 = SyscallEvent<SyscallEventState.Raw>(
            tid = Tid(101),
            syscallName = "OPENAT",
            args = listOf(1L, 2L)
        )

        assertEquals(event1, event2)
        assertEquals(event1.hashCode(), event2.hashCode())
        assertNotEquals(event1, event3)
        assertNotEquals(event1.hashCode(), event3.hashCode())
    }
}
