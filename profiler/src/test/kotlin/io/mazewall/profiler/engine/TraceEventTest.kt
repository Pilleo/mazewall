package io.mazewall.profiler.engine

import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import io.mazewall.profiler.EbpfLoad
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.OpenFlagsEvidence
import io.mazewall.profiler.PathResolutionQuality
import io.mazewall.profiler.ProfileEnvironment
import io.mazewall.profiler.ProfileObservation
import io.mazewall.profiler.ProfileStrategy
import io.mazewall.profiler.ProfilingCoverage
import io.mazewall.profiler.StackAttribution
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TraceEventTest {
    @Test
    fun `behavioral equality preserves distinct failure reasons while ignoring tid`() {
        for ((name, paths) in listOf("OPEN" to emptyList(), "RENAME" to listOf("/tmp/a"))) {
            val denied = evidenceEvent(7, name, paths, PathResolutionFailure.Reason.VmReadDenied)
            val truncated = evidenceEvent(7, name, paths, PathResolutionFailure.Reason.Truncated)
            val otherThread = evidenceEvent(8, name, paths, PathResolutionFailure.Reason.VmReadDenied)
            assertEquals(denied, otherThread)
            assertEquals(denied.hashCode(), otherThread.hashCode())
            assertEquals(2, setOf(denied, truncated, otherThread).size, name)
        }
    }

    @Test
    fun `canonical behavioral keys retain typed evidence for every event variant`() {
        val cases = listOf(
            "OPEN" to emptyList(),
            "RENAME" to listOf("/tmp/a"),
            "OPENAT" to listOf("/tmp/a"),
            "EXECVE" to listOf("/tmp/a"),
            "MMAP" to emptyList(),
            "SOCKET" to emptyList(),
        )
        for ((name, paths) in cases) {
            val denied = evidenceEvent(7, name, paths, PathResolutionFailure.Reason.VmReadDenied)
            val truncated = evidenceEvent(7, name, paths, PathResolutionFailure.Reason.Truncated)
            val otherThread = evidenceEvent(8, name, paths, PathResolutionFailure.Reason.VmReadDenied)
            assertEquals(denied.behavioralKey(), otherThread.behavioralKey())
            assertNotEquals(denied.behavioralKey(), truncated.behavioralKey(), name)
            assertNotEquals(denied, truncated, name)
        }
    }

    private fun evidenceEvent(
        tid: Int,
        name: String,
        paths: List<String>,
        reason: PathResolutionFailure.Reason,
    ): TraceEvent {
        val failures = listOf(PathResolutionFailure(0, reason, "operand inspection"))
        val resolution = if (paths.isEmpty()) PathResolution.Failed(failures) else PathResolution.Partial(paths, failures)
        return TraceEvent.fromSyscallEvent(
            SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(tid), name, List(6) { 0L }, resolution),
        )
    }

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
        assertEquals(event1.behavioralKey(), event2.behavioralKey())
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
    fun `specialized events snapshot mutable path and stack trace lists`() {
        val paths = mutableListOf("/safe")
        val stack = mutableListOf("caller")
        val event = TraceEvent.FsMutation(Tid(42), "UNLINK", paths, stack)
        paths += "/changed"
        stack += "mutated"

        assertEquals(listOf("/safe"), event.paths)
        assertEquals(listOf("caller"), event.stackTrace)
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

    @Test
    fun `openat2 pointer flags stay incomplete through profiling coverage`() {
        val event = assertIs<TraceEvent.Open>(
            TraceEvent(
                tidValue = 7,
                syscallName = "OPENAT2",
                args = longArrayOf(-100, 0x1000, 0x2000, 24),
                paths = listOf("/tmp/openat2-target"),
            ),
        )

        assertEquals(0L, event.flags, "legacy numeric projection remains compatible")
        assertEquals(
            OpenFlagsEvidence.Incomplete(0L, setOf("open_how.flags")),
            event.openFlagsEvidence,
        )

        val observation = assertIs<ProfileObservation.Syscall>(ProfileObservation.fromTraceEvent(event))
        assertNull(observation.openFlags)
        assertEquals(event.openFlagsEvidence, observation.openFlagsEvidence)

        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "openat2 evidence",
            processWide = false,
            observations = listOf(observation),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("test", EbpfLoad.Denied("test")),
        )
        assertEquals(PathResolutionQuality.RESOLVED, coverage.pathResolution)
        assertFalse(coverage.complete)
        assertEquals(listOf("open_how.flags"), coverage.warnings.filter { it.contains("open_how.flags") }.map { "open_how.flags" })
    }
}
