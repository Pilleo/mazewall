package io.mazewall.profiler.internal.attribution

import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierECollectorReadinessTest {
    @Test
    fun `readiness policy waits only after an empty drain and records a ready wakeup`() {
        val source = ReadinessSource(readiness = ArrayDeque(listOf(TierEEventReadiness.READY)))
        val loop = TierECollectorLoop(
            session = TierEProfilerSession(source, processEpoch = 1, definitionReader = { emptyDefinitions() }),
            policy = TierECollectorLoopPolicy.READINESS_DRIVEN,
        )

        loop.pollOnce()

        assertEquals(1, source.awaitCalls)
        assertEquals(1, loop.metrics().readinessWaits)
        assertEquals(1, loop.metrics().readinessWakeups)
        assertEquals(0, loop.metrics().readinessTimeouts)
        loop.close()
    }

    @Test
    fun `timeout remains a scheduling event and never closes the producer`() {
        val source = ReadinessSource(readiness = ArrayDeque(listOf(TierEEventReadiness.TIMEOUT)))
        val loop = TierECollectorLoop(
            session = TierEProfilerSession(source, processEpoch = 1, definitionReader = { emptyDefinitions() }),
            policy = TierECollectorLoopPolicy.READINESS_DRIVEN,
        )

        loop.pollOnce()
        val profile = loop.finish(Path.of("ignored"), producerClosed = false)

        assertEquals(1, loop.metrics().readinessTimeouts)
        assertEquals(1, profile.integrity.lifecycleInvalidations)
        loop.close()
    }

    @Test
    fun `ready wake followed by an empty drain is counted as spurious`() {
        val source = ReadinessSource(
            readiness = ArrayDeque(listOf(TierEEventReadiness.READY, TierEEventReadiness.TIMEOUT)),
        )
        val loop = TierECollectorLoop(
            session = TierEProfilerSession(source, processEpoch = 1, definitionReader = { emptyDefinitions() }),
            policy = TierECollectorLoopPolicy.READINESS_DRIVEN,
        )

        repeat(2) { loop.pollOnce() }

        assertEquals(1, loop.metrics().readinessSpuriousWakeups)
        loop.close()
    }

    @Test
    fun `ready wake consumed by an event is not charged to a later empty drain`() {
        val source = ReadinessSource(
            drains = ArrayDeque(listOf(emptyList(), listOf(event()), emptyList())),
            readiness = ArrayDeque(listOf(TierEEventReadiness.READY, TierEEventReadiness.TIMEOUT)),
        )
        val loop = TierECollectorLoop(
            session = TierEProfilerSession(source, processEpoch = 1, definitionReader = { emptyDefinitions() }),
            policy = TierECollectorLoopPolicy.READINESS_DRIVEN,
        )

        repeat(3) { loop.pollOnce() }

        assertEquals(0, loop.metrics().readinessSpuriousWakeups)
        loop.close()
    }

    @Test
    fun `readiness failure remains a hard collector failure`() {
        val source = ReadinessSource(failure = IllegalStateException("poll failed"))
        val loop = TierECollectorLoop(
            session = TierEProfilerSession(source, processEpoch = 1, definitionReader = { emptyDefinitions() }),
            policy = TierECollectorLoopPolicy.READINESS_DRIVEN,
        )

        assertFailsWith<IllegalStateException> { loop.pollOnce() }
        loop.close()
    }

    private fun emptyDefinitions() = AgentDefinitionFile.Contents(emptyList(), emptyList(), 0)

    private fun event() =
        SyscallInvocationEvent(
        ktimeNs = 1u,
        tgid = 1u,
        tid = 1u,
        syscallNr = 257,
        flags = 0,
        invocationId = 1u,
        sequence = 1u,
        args = List(6) { 0u },
    )

    private class ReadinessSource(
        private val drains: ArrayDeque<List<SyscallInvocationEvent>> = ArrayDeque(),
        private val readiness: ArrayDeque<TierEEventReadiness> = ArrayDeque(),
        private val failure: RuntimeException? = null,
    ) : TierEEventSource {
        var awaitCalls = 0

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

        override fun drainEvents(): List<SyscallInvocationEvent> = drains.removeFirstOrNull() ?: emptyList()

        override fun awaitEvents(timeoutMillis: Int): TierEEventReadiness {
            awaitCalls++
            failure?.let { throw it }
            return readiness.removeFirstOrNull() ?: TierEEventReadiness.TIMEOUT
        }

        override fun readLossCount(): Long = 0

        override fun readMarkerFailureCount(): Long = 0

        override fun readMarkerTransitionCount(): Long = 0

        override fun readLastMarkerPidTgid(): Long = 0

        override fun close() = Unit
    }
}
