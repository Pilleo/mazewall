package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
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

    @Test
    fun `marker detach invalidates attribution without closing session`() {
        val source = FakeTierEEventSource()
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        )

        session.detachMarker()

        assertEquals(1, source.detachMarkerCalls)
        assertEquals(0, source.closeCalls)
        session.close()
    }

    @Test
    fun `live dictionary publishes definitions before session finish`() {
        val writes = mutableListOf<ByteArray>()
        val stack = StackDefinition(
            StackTraceId(7),
            listOf(ManagedFrame("app", "Foo", "work", "()V", 1, ManagedFrameKind.JAVA)),
            StackCaptureQuality.COMPLETE,
        )
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(),
            processEpoch = 1,
            definitionReader = {
                AgentDefinitionFile.Contents(
                    stacks = listOf(stack),
                    invocations = emptyList(),
                    agentLosses = 0,
                )
            },
            dictionarySink = DictionaryBatchSink { batch ->
                writes += batch.bytes
                DictionarySinkResult.Committed
            },
        )

        session.enableLiveDictionary(Path.of("live-definitions"))
        session.poll()

        assertEquals(1, writes.size)
        assertTrue(writes.single().isNotEmpty())
        session.close()
    }

    @Test
    fun `terminal reconciliation retains native admission and capture outcomes`() {
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(),
            processEpoch = 1,
            definitionReader = {
                AgentDefinitionFile.Contents(
                    stacks = emptyList(),
                    invocations = listOf(
                        InvocationDefinition(InvocationId(1), null, StackTraceId(7)),
                        InvocationDefinition(InvocationId(2), null, null, CaptureFailure.STACK_WALK_FAILED),
                    ),
                    agentLosses = 1,
                )
            },
        )

        val profile = session.finish(Path.of("ignored"))

        assertEquals(2, profile.integrity.reconciliation.admittedInvocations)
        assertEquals(1, profile.integrity.reconciliation.exactInvocations)
        assertEquals(1, profile.integrity.reconciliation.failedInvocations)
        assertEquals(0, profile.integrity.reconciliation.kernelActiveObservations)
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
    var detachMarkerCalls = 0

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

    override fun detachMarker() {
        detachMarkerCalls++
    }

    override fun drainEvents(): List<SyscallInvocationEvent> = events.removeFirstOrNull().orEmpty()

    override fun readLossCount(): Long = 0

    override fun readMarkerFailureCount(): Long = 0

    override fun readMarkerTransitionCount(): Long = 0

    override fun readLastMarkerPidTgid(): Long = 0

    override fun close() {
        closeCalls++
    }
}
