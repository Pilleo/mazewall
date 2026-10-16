package io.mazewall.profiler.internal.attribution

import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TierECollectorRuntimeTest {
    @Test
    fun `runtime starts once and caches one terminal result`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())

        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        assertFailsWith<IllegalStateException> { runtime.start() }

        val first = runtime.finish(artifact, TierEProducerClosure.PROVEN)

        assertSame(first, runtime.finish(artifact, TierEProducerClosure.PROVEN))
        assertEquals(TierECollectorServiceState.TERMINAL, runtime.metrics().state)
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `runtime rejects a mismatched artifact as incomplete evidence`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val wrongArtifact = TierEDefinitionArtifact(
            Path.of("ignored"),
            SessionEpoch(session.currentSessionEpoch().value + 1),
        )

        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))

        val result = runtime.finish(wrongArtifact, TierEProducerClosure.PROVEN)

        assertEquals(CaptureGuarantee.INCOMPLETE, result.profile.integrity.guarantee)
        assertFailsWith<IllegalStateException> {
            runtime.finish(
                TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch()),
                TierEProducerClosure.PROVEN,
            )
        }
    }

    @Test
    fun `runtime terminal barrier detaches then drains before it closes the source`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())

        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        runtime.finish(artifact, TierEProducerClosure.UNPROVEN)

        val detach = source.operations.indexOf("detach")
        val finalDrain = source.operations.lastIndexOf("drain")
        val close = source.operations.indexOf("close")
        assertTrue(detach >= 0)
        assertTrue(detach < finalDrain)
        assertTrue(finalDrain < close)
    }

    @Test
    fun `terminal sink failure makes retained result transport incomplete`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())
        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        val result = runtime.finish(artifact, TierEProducerClosure.PROVEN)

        val incomplete = result.withTerminalDeliveryFailure()

        assertEquals(1, incomplete.profile.integrity.terminalDeliveryFailures)
        assertFalse(incomplete.profile.integrity.verdictDimensions.transportComplete)
        assertEquals(CaptureGuarantee.INCOMPLETE, incomplete.profile.integrity.guarantee)
    }

    @Test
    fun `runtime can finish from a prevalidated artifact without reopening its path`() {
        val source = RuntimeSource()
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { error("prevalidated artifact must not reopen its path") },
            configuredSessionEpoch = SessionEpoch(1),
        )
        val runtime = TierECollectorRuntime(session)

        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))

        val result = runtime.finish(
            AgentDefinitionFile.Contents(
                emptyList(),
                emptyList(),
                0,
                publication = SnapshotPublication(1, 0, closed = true, sessionEpoch = SessionEpoch(1)),
            ),
            TierEProducerClosure.PROVEN,
        )

        assertEquals(CaptureGuarantee.FULL_STREAM, result.profile.integrity.guarantee)
    }

    private fun session(source: RuntimeSource) =
        TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { AgentDefinitionFile.Contents(emptyList(), emptyList(), 0) },
        )
}

private class RuntimeSource : TierEEventSource {
    val drainStarted = CountDownLatch(1)
    val operations = mutableListOf<String>()
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

    override fun detachMarker() {
        synchronized(operations) { operations += "detach" }
    }

    override fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean = true

    override fun drainEvents(): List<SyscallInvocationEvent> {
        drainStarted.countDown()
        synchronized(operations) { operations += "drain" }
        return emptyList()
    }

    override fun readLossCount(): Long = 0

    override fun readMarkerFailureCount(): Long = 0

    override fun readMarkerTransitionCount(): Long = 0

    override fun readLastMarkerPidTgid(): Long = 0

    override fun close() {
        closeCalls++
        synchronized(operations) { operations += "close" }
    }
}
