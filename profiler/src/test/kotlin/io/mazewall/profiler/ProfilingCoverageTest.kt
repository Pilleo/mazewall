package io.mazewall.profiler

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ProfilingCoverageTest {
    @Test
    fun `evidence reports complete and incomplete profiles honestly`() {
        val complete = ProfilingCoverage.absent().copy(complete = true, warnings = emptyList())
        assertSame(ProfileEvidence.Complete, complete.evidence())

        val incomplete = ProfilingCoverage.absent().copy(warnings = emptyList())
        val evidence = assertIs<ProfileEvidence.Incomplete>(incomplete.evidence())
        assertEquals(listOf("profile is incomplete"), evidence.reasons)
    }

    @Test
    fun `prior failed path resolution makes a complete profile incomplete`() {
        val current = ProfilingCoverage.absent().copy(
            pathResolution = PathResolutionQuality.RESOLVED,
            complete = true,
            warnings = emptyList(),
        )
        val prior = ProfilingCoverage.absent().copy(pathResolution = PathResolutionQuality.FAILED)

        val retained = current.retainStricterPathResolution(prior)

        assertEquals(PathResolutionQuality.FAILED, retained.pathResolution)
        assertEquals(false, retained.complete)
        assertEquals(listOf("prior USER_NOTIF path resolution was FAILED"), retained.warnings)
    }

    @Test
    fun `already stricter path resolution keeps the original coverage instance`() {
        val current = ProfilingCoverage.absent().copy(pathResolution = PathResolutionQuality.MIXED)
        val prior = ProfilingCoverage.absent().copy(pathResolution = PathResolutionQuality.RESOLVED)

        assertSame(current, current.retainStricterPathResolution(prior))
    }

    @Test
    fun `eBPF inference records missing drain events and io uring evidence`() {
        val coverage =
            ProfilingCoverage.infer(
                strategy = ProfileStrategy.EBPF,
                strategyReason = "test",
                processWide = true,
                observations = emptyList(),
                stacks = StackAttribution.CAPTURED,
                droppedEvents = 2,
                drainComplete = false,
                environment = ProfilingCoverage.absent().environment,
            )

        assertEquals(false, coverage.complete)
        assertEquals(
            listOf(
                "event drain did not complete",
                "dropped 2 events",
                "eBPF did not observe io_uring; destinations are unproven",
            ),
            coverage.warnings,
        )
    }
}
