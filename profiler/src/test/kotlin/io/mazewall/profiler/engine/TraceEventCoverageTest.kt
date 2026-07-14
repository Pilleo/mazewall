package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import org.junit.jupiter.api.Test
import kotlin.test.*

class TraceEventCoverageTest {

    @Test
    fun testTraceEventGenericEqualityAndHashCode() {
        val tid1 = Tid(1)
        val tid2 = Tid(2)
        val event1 = TraceEvent.Generic(tid1, "GETPID", listOf(1L))
        val event2 = TraceEvent.Generic(tid2, "GETPID", listOf(1L))
        val event3 = TraceEvent.Generic(tid1, "GETPID", listOf(2L))

        assertEquals(event1, event2, "TID should be ignored in Generic equality")
        assertNotEquals(event1, event3)
        assertEquals(event1.hashCode(), event2.hashCode())

        assertFalse(event1.equals(null))
        assertFalse(event1.equals("string"))
    }

    @Test
    fun testTraceEventOpenEqualityAndHashCode() {
        val tid1 = Tid(1)
        val tid2 = Tid(2)
        val event1 = TraceEvent.Open(tid1, "OPEN", "/tmp", 1L, 0)
        val event2 = TraceEvent.Open(tid2, "OPEN", "/tmp", 1L, 0)
        val event3 = TraceEvent.Open(tid1, "OPEN", "/etc", 1L, 0)

        assertEquals(event1, event2)
        assertNotEquals(event1, event3)
        assertEquals(event1.hashCode(), event2.hashCode())

        assertFalse(event1.equals(null))
        assertFalse(event1.equals("string"))
    }

    @Test
    fun testTraceEventExecEqualityAndHashCode() {
        val tid1 = Tid(1)
        val event1 = TraceEvent.Exec(tid1, "EXECVE", "/bin/ls")
        val event2 = TraceEvent.Exec(tid1, "EXECVE", "/bin/ls")
        val event3 = TraceEvent.Exec(tid1, "EXECVE", "/bin/sh")

        assertEquals(event1, event2)
        assertNotEquals(event1, event3)
        assertEquals(event1.hashCode(), event2.hashCode())

        assertFalse(event1.equals(null))
        assertFalse(event1.equals("string"))
    }

    @Test
    fun testTraceEventMmapPropertiesAndEquality() {
        val tid1 = Tid(1)
        val event1 = TraceEvent.Mmap(tid1, 0L, 4096L, 7, 0, 0, 0L)
        val event2 = TraceEvent.Mmap(tid1, 0L, 4096L, 7, 0, 0, 0L)

        assertTrue(event1.isExecutable)
        assertEquals(event1, event2)
        assertEquals(event1.hashCode(), event2.hashCode())

        val event3 = TraceEvent.Mmap(tid1, 0L, 4096L, 3, 0, 0, 0L)
        assertFalse(event3.isExecutable)

        assertFalse(event1.equals(null))
        assertFalse(event1.equals("string"))
    }

    @Test
    fun testTraceEventSocketPropertiesAndEquality() {
        val tid1 = Tid(1)
        val event1 = TraceEvent.Socket(tid1, 2, 1, 0) // AF_INET
        val event2 = TraceEvent.Socket(tid1, 2, 1, 0)

        assertTrue(event1.isIpSocket)
        assertEquals(event1, event2)
        assertEquals(event1.hashCode(), event2.hashCode())

        val event3 = TraceEvent.Socket(tid1, 1, 1, 0) // AF_UNIX
        assertFalse(event3.isIpSocket)

        assertFalse(event1.equals(null))
        assertFalse(event1.equals("string"))
    }

    @Test
    fun testTraceEventFsMutationEquality() {
        val tid1 = Tid(1)
        val event1 = TraceEvent.FsMutation(tid1, "MKDIR", listOf("/tmp/d"))
        val event2 = TraceEvent.FsMutation(tid1, "MKDIR", listOf("/tmp/d"))

        assertEquals(event1, event2)
        assertEquals(event1.hashCode(), event2.hashCode())

        assertFalse(event1.equals(null))
        assertFalse(event1.equals("string"))
    }

    @Test
    fun testTraceEventInvokeFactoryBranches() {
        // OPENAT2
        val e1 = TraceEvent(1, "OPENAT2", longArrayOf(0, 0, 0), listOf("/tmp"))
        assertTrue(e1 is TraceEvent.Open)

        // MMAP short args
        val e2 = TraceEvent(1, "MMAP", longArrayOf(0, 0, 0))
        assertTrue(e2 is TraceEvent.Generic)

        // SOCKET short args
        val e3 = TraceEvent(1, "SOCKET", longArrayOf(0, 0))
        assertTrue(e3 is TraceEvent.Generic)

        // FsMutation no paths
        val e4 = TraceEvent(1, "MKDIR", longArrayOf(0, 0), emptyList())
        assertTrue(e4 is TraceEvent.Generic)

        // OPEN short args
        val e5 = TraceEvent(1, "OPEN", longArrayOf())
        assertTrue(e5 is TraceEvent.Generic)
    }
}
