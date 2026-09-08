package io.mazewall.profiler.engine

import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TraceEventTest {
    @Test
    fun `test trace events equality ignores tid`() {
        val event1 = TraceEvent(1001, "OPENAT", longArrayOf(1, 2, 3), listOf("/etc/passwd"))
        val event2 = TraceEvent(1002, "OPENAT", longArrayOf(1, 2, 3), listOf("/etc/passwd"))
        val event3 = TraceEvent(1001, "OPENAT", longArrayOf(1, 2, 4), listOf("/etc/passwd"))

        assertEquals(event1, event2, "Events with different TIDs but same syscall/args/paths should be equal")
        assertEquals(event1.hashCode(), event2.hashCode(), "HashCodes should match for equal events")

        assertNotEquals(event1, event3, "Events with different args should not be equal")
    }

    @Test
    fun `test trace events in a set deduplicate by behavior`() {
        val event1 = TraceEvent(1001, "OPENAT", longArrayOf(1, 2, 3), listOf("/etc/passwd"))
        val event2 = TraceEvent(1002, "OPENAT", longArrayOf(1, 2, 3), listOf("/etc/passwd"))

        val set = setOf(event1, event2)
        assertEquals(1, set.size, "Set should deduplicate events by behavior, ignoring TID")
    }

    @Test
    fun `generic event snapshots mutable collections and JVM stack traces`() {
        val args = mutableListOf(1L)
        val paths = mutableListOf("/safe")
        val event = TraceEvent.Generic(Tid(42), "UNKNOWN", args, paths)
        val stack = arrayOf(StackTraceElement("Owner", "method", "Owner.kt", 1))

        event.jvmStackTrace = stack
        args += 2L
        paths += "/changed"
        stack[0] = StackTraceElement("Other", "method", "Other.kt", 2)

        assertEquals(listOf(1L), event.args)
        assertEquals(listOf("/safe"), event.paths)
        assertEquals("Owner", event.jvmStackTrace?.single()?.className)
        event.jvmStackTrace = null
        assertNull(event.jvmStackTrace)
    }

    @Test
    fun `typed profiler boundaries retain known and unknown raw values`() {
        assertEquals(ObservedSyscall.Known(Syscall.OPENAT), ObservedSyscall.parse("openat"))
        assertEquals(ObservedSyscall.Unknown("future_call"), ObservedSyscall.parse("future_call"))

        val socket = TraceEvent.Socket(Tid(7), domain = 2, type = 1, protocol = 6)
        assertEquals(AddressFamily.Known.INET, socket.addressFamily)
        assertEquals(SocketType(1), socket.socketType)
        assertEquals(IpProtocol(6), socket.ipProtocol)
    }
}
