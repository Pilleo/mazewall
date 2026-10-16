package io.mazewall.profiler.internal.attribution

import io.mazewall.profiler.tierE.daemon.UsdtProbeNote
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Controlled fault fixture: a singleton edge is retained in the bounded kernel
 * map, its ring delivery is deliberately lost, and the collector must still
 * resolve, acknowledge, and cover it while ring transport stays incomplete.
 */
class TierECoverageRecoveryFixtureTest {
    private val sessionTag = 0x4d5a
    private val stackId = StackTraceId(7)
    private val invocationId = InvocationId(55)

    private fun recoveryEntry(): RecoveryMapEntry {
        val key = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        key
            .putLong(sessionTag.toLong())
            .putLong(7)
            .putInt(257)
            .putInt(0)
        val value = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
        value
            .putLong(999L)
            .putInt(100)
            .putInt(101)
            .putInt(257)
            .putInt(0)
        value.putLong(55L).putLong(2L)
        listOf(-100L, 1L, 2L, 3L, 4L, 5L).forEach(value::putLong)
        value.putInt(1).putInt(0)
        return RecoveryMapEntry(key.array(), value.array())
    }

    private fun definitions() =
        AgentDefinitionFile.Contents(
            listOf(StackDefinition(stackId, emptyList(), StackCaptureQuality.COMPLETE)),
            listOf(
                InvocationDefinition(
                    invocationId = invocationId,
                    parentInvocationId = null,
                    stackTraceId = stackId,
                    captureFailure = null,
                    executionContext = null,
                ),
            ),
            0,
        )

    private fun liveDefinitions() =
        AgentDefinitionFile.Contents(
        definitions().stacks,
        definitions().invocations,
        0,
        publication = SnapshotPublication(1, invocationId.value, false, SessionEpoch(sessionTag.toLong())),
        sourceByteLength = 100,
        sourceSha256 = "a".repeat(64),
    )

    private class FaultSource(
        val entry: RecoveryMapEntry,
    ) : TierEEventSource {
        var scans = 0

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

        override fun detachMarker() = Unit

        override fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean = true

        // The singleton ring event never arrives: forced delivery loss.
        override fun drainEvents(): List<SyscallInvocationEvent> = emptyList()

        override fun readLossCount(): Long = 1

        override fun readMarkerFailureCount(): Long = 0

        override fun readMarkerTransitionCount(): Long = 1

        override fun readExactAdmissionCount(): Long = 1

        override fun readLastMarkerPidTgid(): Long = 0

        override fun readRecoveryInsertionFailureCount(): Long = 0

        override fun recoveryReader(): RecoveryMapReader? =
            RecoveryMapReader { _, _ ->
                scans++
                RecoveryScanPage(listOf(entry), null, complete = true)
            }

        override fun close() = Unit
    }

    private fun session(
        mode: CoverageRecoveryMode,
        source: FaultSource,
    ) = TierEProfilerSession(
        source = source,
        processEpoch = 999,
        options = TierEOptions(
            benchmarkPhases = true,
            markerVersion = MarkerVersion.V2,
            coverageRecoveryMode = mode,
        ),
        definitionReader = { definitions() },
        selectiveDefinitionReader = { _, _ -> definitions() },
        configuredSessionEpoch = SessionEpoch(sessionTag.toLong()),
    )

    @Test
    fun `deliberately ring dropped singleton is recovered with incomplete ring verdict`() {
        val source = FaultSource(recoveryEntry())
        val session = session(CoverageRecoveryMode.RECOVER, source)
        session.installAndAttach(100, sessionTag, Path.of("ignored"), 0)

        session.poll()
        val profile = session.finish(Path.of("ignored"), producerClosed = true)

        // No ring event was fabricated: the ring stream stays empty and lossy.
        assertEquals(0, profile.syscalls.size)
        assertEquals(1, profile.integrity.observedLosses)
        assertEquals(1, profile.recoveredCoverage.size)
        assertEquals(
            stackId,
            profile.recoveredCoverage
            .single()
            .stackDefinition.stackTraceId,
        )
        assertEquals(
            invocationId,
            profile.recoveredCoverage
            .single()
            .candidate.invocationId,
        )

        val evidence = profile.integrity.recoveryEvidence
        assertEquals(optionsCapacity(), evidence.mapCapacity)
        assertTrue(evidence.entriesObserved >= 1)
        assertEquals(0, evidence.insertionFailures)
        assertEquals(0, evidence.pendingCandidates)
        assertEquals(1, evidence.recoveredEdges)
        assertEquals(1, evidence.mapFirstAcknowledgedEdges)
        assertTrue(evidence.lastRecoveryLatencyNanos >= 0)
        assertTrue(evidence.maxRecoveryLatencyNanos >= evidence.lastRecoveryLatencyNanos)
        assertTrue(evidence.collectorRetainedPeakBytes > 0)
        assertTrue(session.recoveryComplete())
    }

    @Test
    fun `shadow resolves without committing recovered coverage`() {
        val source = FaultSource(recoveryEntry())
        val session = session(CoverageRecoveryMode.SHADOW, source)
        session.installAndAttach(100, sessionTag, Path.of("ignored"), 0)

        session.poll()
        val profile = session.finish(Path.of("ignored"), producerClosed = true)

        assertEquals(0, profile.syscalls.size)
        assertTrue(profile.recoveredCoverage.isEmpty())
        val evidence = profile.integrity.recoveryEvidence
        assertTrue(evidence.entriesObserved >= 1)
        assertEquals(0, evidence.recoveredEdges)
        assertEquals(0, evidence.mapFirstAcknowledgedEdges)
        assertTrue(session.recoveryComplete())
    }

    @Test
    fun `live native dictionary makes retained singleton resolvable before terminal publication`() {
        val source = FaultSource(recoveryEntry())
        val session = session(CoverageRecoveryMode.RECOVER, source)
        session.installAndAttach(100, sessionTag, Path.of("ignored"), 0)
        var reads = 0
        session.enableNativeLiveSnapshots { maxBytes ->
            assertTrue(maxBytes >= 100)
            reads++
            liveDefinitions()
        }

        session.poll()
        assertEquals(1, session.nativeLivePublicationCount())
        val profile = session.finish(Path.of("ignored"), producerClosed = true)
        assertEquals(1, profile.recoveredCoverage.size)
        assertEquals(1, reads)
    }

    private fun optionsCapacity(): Int = CoverageRecoveryMode.DEFAULT_MAP_CAPACITY
}
