package io.mazewall.enforcer.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewedOperationArtifactTest {
    private fun binding(
        generation: String = "resources-1",
        member: String = "parse(Ljava/lang/String;)Ljava/lang/String;",
    ): OperationBinding = OperationBinding("parser", OperationAdapter.CALLABLE, "a".repeat(64), generation, mapOf("entryPoint" to member))

    private fun candidate(): OperationPolicyDocument {
        val policy = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build())
        return OperationPolicyDocument(binding(), policy, setOf(CoverageFacet.ASYNC_EXECUTION), mapOf(CoverageFacet.ASYNC_EXECUTION to "Materialized nonsuspending operation only"))
    }

    @Test
    fun `trusted exact approval retains reviewed coverage exceptions`() {
        val document = candidate()
        val approval = TrustedPolicyReview("operator-review", document.sha256(), setOf(CoverageFacet.ASYNC_EXECUTION))
        val reviewed = ReviewedOperationArtifact.load(document.toJson(), approval, binding())
        assertEquals("parser", reviewed.binding.operationId)
        assertEquals(setOf(CoverageFacet.ASYNC_EXECUTION), reviewed.document.missingCoverage)
        assertEquals("Materialized nonsuspending operation only", reviewed.document.reviewedExceptions[CoverageFacet.ASYNC_EXECUTION])
    }

    @Test
    fun `document metadata cannot manufacture approval or widen reviewed exceptions`() {
        val document = candidate()
        assertFailsWith<IllegalArgumentException> {
            ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("review", "b".repeat(64), setOf(CoverageFacet.ASYNC_EXECUTION)), binding())
        }
        assertFailsWith<IllegalArgumentException> {
            ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("review", document.sha256(), emptySet()), binding())
        }
    }

    @Test
    fun `stale resource generation and mismatched member reject before preparation`() {
        val document = candidate()
        val approval = TrustedPolicyReview("review", document.sha256(), setOf(CoverageFacet.ASYNC_EXECUTION))
        assertFailsWith<IllegalArgumentException> { ReviewedOperationArtifact.load(document.toJson(), approval, binding("resources-2")) }
        assertFailsWith<IllegalArgumentException> { ReviewedOperationArtifact.load(document.toJson(), approval, binding(member = "parse(I)I")) }
    }

    @Test
    fun `partial evidence cannot omit a specifically reviewed exception`() {
        val policy = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build())
        val document = OperationPolicyDocument(binding(), policy, setOf(CoverageFacet.FILE_PATHS), emptyMap())
        assertFailsWith<IllegalArgumentException> {
            ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("review", document.sha256(), setOf(CoverageFacet.FILE_PATHS)), binding())
        }
    }

    @Test
    fun `approval fields inside document and unknown security fields reject`() {
        val document = candidate()
        val json = document.toJson()
        assertFailsWith<IllegalArgumentException> { OperationPolicyDocument.fromJson(json.dropLast(1) + ",\"approved\":true}") }
        assertFailsWith<IllegalArgumentException> { OperationPolicyDocument.fromJson(json.replace("\"formatVersion\":1", "\"formatVersion\":2")) }
    }
}
