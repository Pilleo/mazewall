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

    @Test
    fun `test defensive copy isolates event from mutable input list modifications`() {
        val mutableArgs = mutableListOf(1L, 2L)
        val mutablePaths = mutableListOf("/etc/passwd")
        val mutableStackTrace = mutableListOf("io.mazewall.Test.run(Test.kt:10)")

        val event = SyscallEvent<SyscallEventState.Raw>(
            tid = Tid(100),
            syscallName = "OPENAT",
            args = mutableArgs,
            paths = mutablePaths,
            stackTrace = mutableStackTrace,
        )

        val set = hashSetOf(event)

        mutableArgs.add(3L)
        mutablePaths.add("/etc/shadow")
        mutableStackTrace.add("io.mazewall.Test.main(Test.kt:20)")

        assertEquals(listOf(1L, 2L), event.args)
        assertEquals(listOf("/etc/passwd"), event.paths)
        assertEquals(listOf("io.mazewall.Test.run(Test.kt:10)"), event.stackTrace)
        assertEquals(true, set.contains(event), "Hash-based set should retain stable lookup after caller mutates original lists")
    }

    @Test
    fun `test resolved extension function defensively copies paths`() {
        val rawEvent = SyscallEvent<SyscallEventState.Raw>(
            tid = Tid(100),
            syscallName = "OPENAT",
            args = listOf(1L, 2L),
        )

        val mutablePaths = mutableListOf("/etc/hosts")
        val resolved = rawEvent.resolved(mutablePaths)

        mutablePaths.add("/etc/resolv.conf")

        assertEquals(listOf("/etc/hosts"), resolved.paths)
    }
}
