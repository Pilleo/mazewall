package io.mazewall.profiler.attribution

import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TierEProfilerSessionLifecycleTest {
    @Test
    fun `finish is cached and polling after finish fails`() {
        val source = FakeTierEEventSource()
        var definitionReads = 0
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            options = TierEOptions(),
            definitionReader = {
                definitionReads++
                emptyDefinitions()
            },
        )

        val first = session.finish(Path.of("ignored"))
        val second = session.finish(Path.of("different-and-never-read"))

        assertTrue(first === second)
        assertEquals(1, definitionReads)
        assertFailsWith<IllegalStateException> { session.poll() }
    }

    @Test
    fun `retained overflow drops new records and marks cached result incomplete`() {
        val source = FakeTierEEventSource(
            events = ArrayDeque(listOf(listOf(event(1uL)), listOf(event(2uL)))),
        )
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            options = TierEOptions(maxRetainedRecords = 1),
            definitionReader = { emptyDefinitions() },
        )

        session.poll()
        session.poll()
        val profile = session.finish(Path.of("ignored"))

        assertEquals(1, profile.syscalls.size)
        assertEquals(1, profile.integrity.observedLosses)
        assertEquals(CaptureGuarantee.INCOMPLETE, profile.integrity.guarantee)
    }

    @Test
    fun `close is terminal and idempotent`() {
        val source = FakeTierEEventSource()
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        )

        session.close()
        session.close()

        assertEquals(1, source.closeCalls)
        assertFailsWith<IllegalStateException> { session.poll() }
        assertFailsWith<IllegalStateException> { session.finish(Path.of("ignored")) }
    }

    private fun emptyDefinitions() = AgentDefinitionFile.Contents(emptyList(), emptyList(), 0)

    private fun event(sequence: ULong) =
        SyscallInvocationEvent(
        ktimeNs = sequence,
        tgid = 1u,
        tid = 2u,
        syscallNr = 257,
        flags = 0,
        sequence = sequence,
        invocationId = 0u,
        args = List(6) { 0u },
    )
}

private class FakeTierEEventSource(
    private val events: ArrayDeque<List<SyscallInvocationEvent>> = ArrayDeque(),
) : TierEEventSource {
    var closeCalls = 0

    override fun install(
        targetPid: Int,
        sessionTag: Int,
        options: TierEOptions,
    ) = Unit

    override fun attachMarker(
        targetPid: Int,
        agentLibrary: Path,
        markerOffset: Long,
    ) = Unit

    override fun drainEvents(): List<SyscallInvocationEvent> = events.removeFirstOrNull().orEmpty()

    override fun readLossCount(): Long = 0

    override fun readMarkerFailureCount(): Long = 0

    override fun readMarkerTransitionCount(): Long = 0

    override fun readLastMarkerPidTgid(): Long = 0

    override fun close() {
        closeCalls++
    }
}
