package io.mazewall.profiler.hotspot

import io.mazewall.profiler.api.IncompleteProfileException
import io.mazewall.profiler.internal.hotspot.HotSpotCoverageAdapter
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotSpotCoverageTest {
    @Test
    fun `observed native reserve failures remain a lower bound rather than fabricated zero`() {
        val snapshot = HotSpotEvidenceAssemblerTest.replay(
            HotSpotEvidenceAssemblerTest.event(1),
            HotSpotEvidenceAssemblerTest.terminal(1),
            HotSpotEvidenceAssemblerTest.summary(1).replace("\"observed\":0", "\"observed\":7"),
        )
        kotlin.test.assertEquals(7, HotSpotCoverageAdapter.toResult(snapshot).coverage.droppedEvents)
        assertFalse(snapshot.collectionLossComplete)
    }

    @Test
    fun `successful replay drains cannot produce complete policy evidence`() {
        val snapshot = HotSpotEvidenceAssemblerTest.replay(
            HotSpotEvidenceAssemblerTest.event(1),
            HotSpotEvidenceAssemblerTest.terminal(1),
            HotSpotEvidenceAssemblerTest.summary(1),
        )
        val result = HotSpotCoverageAdapter.toResult(snapshot)
        assertFalse(result.coverage.complete)
        assertTrue(result.coverage.warnings.any { it.contains("selected openat") })
        assertTrue(result.coverage.warnings.any { it.contains("loss") })
        assertFailsWith<IncompleteProfileException> { result.toDedicatedWorkerPolicies() }
    }
}
