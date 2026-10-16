package io.mazewall.profiler.hotspot

import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.HotSpotProfilingSession
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.api.ProfileOptions
import io.mazewall.profiler.api.ProfileStrategy
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorProcess
import io.mazewall.profiler.internal.hotspot.HotSpotSessionFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import io.mazewall.profiler.internal.hotspot.HotSpotProfilingSession as OwnedSession

class HotSpotWorkloadFacadeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `workload value and typed owner are preserved after validated ready`() {
        open("success").use { profiler ->
            val result = profiler.profile {
                assertFalse(requireNotNull(profiler.snapshot().structuredEvidence).finalized)
                "value"
            }
            assertEquals("value", result.value)
            assertEquals(ProfileStrategy.HOTSPOT_AGENTLESS, result.coverage.strategy)
            assertFalse(result.coverage.complete)
            assertTrue(requireNotNull(result.structuredEvidence).finalized)
            assertSame(result.structuredEvidence, result.copy(value = "copy").structuredEvidence)
        }
    }

    @Test
    fun `workload and failed cleanup causes are both preserved`() {
        val user = IllegalArgumentException("user workload failure")
        open("malformed").use { profiler ->
            val caught = assertFailsWith<IllegalArgumentException> { profiler.profile<Unit> { throw user } }
            assertSame(user, caught)
            val cleanup = caught.suppressed.filterIsInstance<HotSpotProfilingException>().single()
            assertEquals(1uL, requireNotNull(cleanup.partialResult?.structuredEvidence).pendingOccurrences)
            assertFalse(cleanup.pendingOwnership)
        }
    }

    private fun open(mode: String): MazewallProfiler {
        val argv = HotSpotChildFixture.argv(mode, directory)
        val factory = HotSpotSessionFactory { _, _ -> HotSpotProfilingSession(OwnedSession(HotSpotCollectorProcess.attach(argv, 123, 456uL))) }
        return MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true), HotSpotOptions(directory), factory)
    }
}
