package io.mazewall.profiler.hotspot

import io.mazewall.profiler.api.BillOfBehavior
import io.mazewall.profiler.api.IncompleteProfileException
import io.mazewall.profiler.api.ProfileDocument
import io.mazewall.profiler.api.ProfileIntegrity
import io.mazewall.profiler.api.ProfileStrategy
import io.mazewall.profiler.api.ProfilingCoverage
import io.mazewall.profiler.api.ProfilingResult
import io.mazewall.profiler.api.StructuredProfileEvidence
import io.mazewall.profiler.internal.hotspot.HotSpotCoverageAdapter
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class HotSpotResultCompatibilityTest {
    @Test
    fun `legacy public result constructor and copy descriptors remain present`() {
        val type = ProfilingResult::class.java
        type.getConstructor(Any::class.java, BillOfBehavior::class.java, Map::class.java, ProfilingCoverage::class.java, List::class.java)
        type.getConstructor(
            Any::class.java,
            BillOfBehavior::class.java,
            Map::class.java,
            ProfilingCoverage::class.java,
            List::class.java,
            Int::class.javaPrimitiveType,
            kotlin.jvm.internal.DefaultConstructorMarker::class.java,
        )
        type.getDeclaredMethod("copy", Any::class.java, BillOfBehavior::class.java, Map::class.java, ProfilingCoverage::class.java, List::class.java)
        type.getDeclaredMethod(
            "copy\$default",
            type,
            Any::class.java,
            BillOfBehavior::class.java,
            Map::class.java,
            ProfilingCoverage::class.java,
            List::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
    }

    @Test
    fun `public typed owner survives copy and remains ineligible for worker policies`() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).use { HotSpotEvidenceAssembler().read(it) }
        val result = HotSpotCoverageAdapter.toLiveResult(raw)
        val owner = requireNotNull(result.structuredEvidence)
        assertSame(owner, result.copy().structuredEvidence)
        assertFalse(result.coverage.complete)
        assertEquals(ProfileStrategy.HOTSPOT_AGENTLESS, result.coverage.strategy)
        assertFailsWith<IncompleteProfileException> { result.toDedicatedWorkerPolicies() }
        assertEquals(ProfileStrategy.HOTSPOT_AGENTLESS, ProfileDocument.fromJson(result.toProfileDocumentJson()).coverage.strategy)
        val decoded = StructuredProfileEvidence.fromJson(owner.toJson())
        assertEquals(
            owner.records
            .first()
            .evidence.frames
            .map { it.rawPosition },
                decoded.records
                .first()
                .evidence.frames
                .map { it.rawPosition },
        )
        assertEquals(
            owner.records
            .first()
            .evidence.frames
            .map { it.bci },
                decoded.records
                .first()
                .evidence.frames
                .map { it.bci },
        )
        assertFalse(decoded.collectionLossComplete)
        val suppliedComplete = ProfilingCoverage.absent().copy(integrity = ProfileIntegrity(true))
        val copied = result.copy(coverage = suppliedComplete)
        assertFalse(copied.coverage.complete)
        assertEquals(ProfileStrategy.HOTSPOT_AGENTLESS, copied.coverage.strategy)
        assertFailsWith<IncompleteProfileException> { copied.toPolicy() }
        assertFailsWith<IllegalArgumentException> { StructuredProfileEvidence.fromJson(owner.toJson().replace("\"collectionLossComplete\":false", "\"collectionLossComplete\":true")) }
        assertFailsWith<UnsupportedOperationException> { (owner.records as MutableList).clear() }
        assertEquals(
            -100,
            owner.records
            .first()
            .event.dirfd,
        )
    }
}
