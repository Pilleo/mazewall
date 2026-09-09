package io.mazewall.profiler.engine

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.core.Tid
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@EnabledIfLinuxAndSupported
class SyscallEventCompatibilityTest {
    @Test
    fun `public primary reflection retains parameter types names and independent defaults`() {
        val required = mapOf("tid" to Tid(7), "syscallName" to "READ", "args" to listOf(1L))
        val event = SyscallEventConstructorContract.construct(required)
        assertEquals(SyscallEvent<SyscallEventState.Raw>(Tid(7), "READ", listOf(1L)), event)
        assertEquals(emptyList(), event.paths)
        assertNull(event.stackTrace)
        val stackOnly = SyscallEventConstructorContract.construct(required + ("stackTrace" to listOf("caller")))
        assertEquals(emptyList(), stackOnly.paths)
        assertEquals(listOf("caller"), stackOnly.stackTrace)
        val pathsOnly = SyscallEventConstructorContract.construct(required + ("paths" to listOf("/tmp/a")))
        assertEquals(listOf("/tmp/a"), pathsOnly.paths)
        assertNull(pathsOnly.stackTrace)
    }

    @Test
    fun `named public construction snapshots every caller collection and rejects mutation`() {
        val args = mutableListOf(1L)
        val paths = mutableListOf("/tmp/a")
        val stack = mutableListOf("caller")
        val event = SyscallEvent<SyscallEventState.Resolved>(tid = Tid(7), syscallName = "OPEN", args = args, paths = paths, stackTrace = stack)
        args.clear()
        paths.clear()
        stack.clear()
        assertEquals(listOf(1L), event.args)
        assertEquals(listOf("/tmp/a"), event.paths)
        assertEquals(listOf("caller"), event.stackTrace)
        assertFailsWith<UnsupportedOperationException> { (event.args as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (event.paths as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (event.stackTrace as MutableList).clear() }
    }

    @Test
    fun `typed factory and original path constructor retain every resolution outcome losslessly`() {
        val failure = PathResolutionFailure(0, PathResolutionFailure.Reason.VmReadDenied, "TID 7 EPERM")
        val outcomes = listOf(
            PathResolution.NotApplicable,
            PathResolution.Resolved(listOf("/tmp/a")),
            PathResolution.Partial(listOf("/tmp/a"), listOf(failure)),
            PathResolution.Failed(listOf(failure)),
        )
        for (outcome in outcomes) {
            val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "RENAME", listOf(1L, 2L), outcome)
            val reconstructed = SyscallEvent<SyscallEventState.Resolved>(event.tid, event.syscallName, event.args, event.paths, event.stackTrace)
            assertEquals(outcome, event.pathResolution)
            assertEquals(outcome, reconstructed.pathResolution)
            assertEquals(event, reconstructed)
            assertEquals(event.hashCode(), reconstructed.hashCode())
            assertEquals(outcome.paths, event.paths)
            assertEquals(event.paths, outcome.paths)
            assertEquals(outcome.paths.hashCode(), event.paths.hashCode())
        }
    }

    @Test
    fun `typed evidence and snapshots survive caller mutation without changing tid equality rules`() {
        val args = mutableListOf(1L, 2L)
        val paths = mutableListOf("/tmp/a")
        val stack = mutableListOf("caller")
        val failure = PathResolutionFailure(1, PathResolutionFailure.Reason.DirFdUnresolved, "dirfd=9")
        val failures = mutableListOf(failure)
        val resolution = PathResolution.Partial(paths, failures)
        val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "RENAME", args, resolution, stack)
        val anotherThread = SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(8), "RENAME", args, resolution, stack)
        val differentEvidence = SyscallEvent.fromResolution<SyscallEventState.Resolved>(
            Tid(7),
            "RENAME",
            args,
            PathResolution.Partial(paths, listOf(failure.copy(reason = PathResolutionFailure.Reason.Truncated))),
            stack,
        )
        val events = hashSetOf(event, anotherThread, differentEvidence)
        args.clear()
        paths.clear()
        stack.clear()
        failures.clear()
        assertEquals(listOf(1L, 2L), event.args)
        assertEquals(listOf("/tmp/a"), event.paths)
        assertEquals(listOf("caller"), event.stackTrace)
        assertEquals(listOf(failure), event.pathResolution.failures)
        assertEquals(3, events.size)
        assertEquals(true, event in events)
        assertNotEquals(event, anotherThread)
        assertNotEquals(event, differentEvidence)
        val trace = TraceEvent.fromSyscallEvent(event)
        val otherTrace = TraceEvent.fromSyscallEvent(anotherThread)
        assertEquals(trace, otherTrace)
        assertEquals(trace.hashCode(), otherTrace.hashCode())
        assertNotEquals(trace, TraceEvent.fromSyscallEvent(differentEvidence))
        assertFailsWith<UnsupportedOperationException> { (event.pathResolution.failures as MutableList).clear() }
    }

    @Test
    fun `replacing typed paths or operation explicitly selects the legacy adapter`() {
        val resolution = PathResolution.Partial(listOf("/tmp/a"), listOf(PathResolutionFailure(0, PathResolutionFailure.Reason.Truncated, "unterminated")))
        val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "OPEN", listOf(1L), resolution)
        val replacement = SyscallEvent<SyscallEventState.Resolved>(event.tid, event.syscallName, event.args, listOf("/tmp/a"))
        val operation = SyscallEvent<SyscallEventState.Resolved>(event.tid, "STAT", event.args, event.paths)
        assertIs<PathResolution.Resolved>(replacement.pathResolution)
        assertIs<PathResolution.Resolved>(operation.pathResolution)
    }
}
