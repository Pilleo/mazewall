package io.mazewall.profiler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.core.Tid
import io.mazewall.profiler.engine.SessionEvent

class ProfilerReflectionTest2 {

    @Test
    fun `test SessionEvent functionality`() {
        val notified = SessionEvent.Notified(100L, 1234L, 2L)
        val eventSent = SessionEvent.EventSent(200L, 1234L)
        val ackReceived = SessionEvent.AckReceived(300L, 1234L)
        val continueReplied = SessionEvent.ContinueReplied(400L, 1234L, 0L)
        val errorReplied = SessionEvent.ErrorReplied(500L, 1234L, 104)

        assertNotNull(notified.toString())
        assertNotNull(eventSent.toString())
        assertNotNull(ackReceived.toString())
        assertNotNull(continueReplied.toString())
        assertNotNull(errorReplied.toString())
    }

    @Test
    fun `test trace event open instantiation`() {
        val event = TraceEvent(
            tidValue = 1234,
            syscallName = "OPENAT",
            args = longArrayOf(1, 2, 3),
            paths = listOf("/tmp/test"),
            stackTrace = null
        )
        assertTrue(event is TraceEvent.Open)
        val openEvent = event as TraceEvent.Open
        assertEquals(1234, openEvent.tid.value)
        assertEquals("OPENAT", openEvent.syscallName)
        assertEquals("/tmp/test", openEvent.path)
        assertTrue(openEvent.paths.contains("/tmp/test"))

        val event2 = TraceEvent.invoke(
            tidValue = 1234,
            syscallName = "OPENAT",
            args = longArrayOf(1, 2, 3),
            paths = listOf("/tmp/test"),
            stackTrace = null
        )
        assertEquals(event, event2)
        assertEquals(event.hashCode(), event2.hashCode())
    }

    @Test
    fun `test trace event exec instantiation`() {
        val event = TraceEvent(
            tidValue = 1234,
            syscallName = "EXECVE",
            args = longArrayOf(1, 2, 3),
            paths = listOf("/bin/sh"),
            stackTrace = null
        )
        assertTrue(event is TraceEvent.Exec)
        val execEvent = event as TraceEvent.Exec
        assertEquals("EXECVE", execEvent.syscallName)
        assertEquals("/bin/sh", execEvent.path)

        val event2 = TraceEvent.invoke(
            tidValue = 1234,
            syscallName = "EXECVE",
            args = longArrayOf(1, 2, 3),
            paths = listOf("/bin/sh"),
            stackTrace = null
        )
        assertEquals(event, event2)
        assertEquals(event.hashCode(), event2.hashCode())
    }

    @Test
    fun `test trace event mmap instantiation`() {
        val event = TraceEvent(
            tidValue = 1234,
            syscallName = "MMAP",
            args = longArrayOf(0, 4096, 7, 34, -1, 0),
            paths = emptyList(),
            stackTrace = null
        )
        assertTrue(event is TraceEvent.Mmap)
        val mmapEvent = event as TraceEvent.Mmap
        assertTrue(mmapEvent.isExecutable)
        assertEquals(4096L, mmapEvent.len)

        val event2 = TraceEvent.invoke(
            tidValue = 1234,
            syscallName = "MMAP",
            args = longArrayOf(0, 4096, 7, 34, -1, 0),
            paths = emptyList(),
            stackTrace = null
        )
        assertEquals(event, event2)
        assertEquals(event.hashCode(), event2.hashCode())
    }

    @Test
    fun `test trace event socket instantiation`() {
        val event = TraceEvent(
            tidValue = 1234,
            syscallName = "SOCKET",
            args = longArrayOf(2, 1, 0),
            paths = emptyList(),
            stackTrace = null
        )
        assertTrue(event is TraceEvent.Socket)
        val socketEvent = event as TraceEvent.Socket
        assertTrue(socketEvent.isIpSocket)
        assertEquals(2, socketEvent.domain)

        val event2 = TraceEvent.invoke(
            tidValue = 1234,
            syscallName = "SOCKET",
            args = longArrayOf(2, 1, 0),
            paths = emptyList(),
            stackTrace = null
        )
        assertEquals(event, event2)
        assertEquals(event.hashCode(), event2.hashCode())
    }

    @Test
    fun `test trace event fsmutation instantiation`() {
        val event = TraceEvent(
            tidValue = 1234,
            syscallName = "MKDIR",
            args = longArrayOf(),
            paths = listOf("/tmp/newdir"),
            stackTrace = null
        )
        assertTrue(event is TraceEvent.FsMutation)
        val fsEvent = event as TraceEvent.FsMutation
        assertEquals("MKDIR", fsEvent.syscallName)
        assertTrue(fsEvent.paths.contains("/tmp/newdir"))

        val event2 = TraceEvent.invoke(
            tidValue = 1234,
            syscallName = "MKDIR",
            args = longArrayOf(),
            paths = listOf("/tmp/newdir"),
            stackTrace = null
        )
        assertEquals(event, event2)
        assertEquals(event.hashCode(), event2.hashCode())
    }

    @Test
    fun `test generic trace event instantiation`() {
        val event = TraceEvent(
            tidValue = 1234,
            syscallName = "UNKNOWN",
            args = longArrayOf(),
            paths = emptyList(),
            stackTrace = null
        )
        assertTrue(event is TraceEvent.Generic)
        val genericEvent = event as TraceEvent.Generic
        assertEquals("UNKNOWN", genericEvent.syscallName)

        val event2 = TraceEvent.invoke(
            tidValue = 1234,
            syscallName = "UNKNOWN",
            args = longArrayOf(),
            paths = emptyList(),
            stackTrace = null
        )
        assertEquals(event, event2)
        assertEquals(event.hashCode(), event2.hashCode())
    }
}
