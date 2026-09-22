package io.mazewall.profiler.tierE.daemon

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TierECoverageManifestFileSinkTest {
    @Test
    fun `writes an atomically named bounded benchmark coverage manifest`() {
        val root = Files.createTempDirectory("tier-e-coverage")
        val manifest = manifest("orders")

        TierECoverageManifestFileSink(root).accept(manifest)

        val output = root.resolve("orders-19802.tier-e-coverage.json")
        assertTrue(Files.isRegularFile(output))
        assertTrue(output.readText().contains("\"policy_eligibility\":false"))
        assertTrue(output.readText().contains("\"acknowledged_count\":1"))
    }

    @Test
    fun `rejects unsafe identity and duplicate edge keys`() {
        assertFailsWith<IllegalArgumentException> { manifest("../escape") }
        assertFailsWith<IllegalArgumentException> {
            manifest("orders").copy(edges = listOf(edge(), edge()))
        }
    }

    private fun manifest(registrationId: String) =
        TierECoverageManifest(
        registrationId = registrationId,
        sessionEpoch = 19_802L,
        expectedOracleSha256 = "a".repeat(64),
        terminalReportSha256 = "b".repeat(64),
        evidenceIntegrity = "COMPLETE",
        captureDomainValidity = true,
        transportCompleteness = true,
        attributionCoverage = true,
        dictionaryIntegrity = true,
        lifecycleValidity = true,
        edges = listOf(edge()),
    )

    private fun edge() = TierECoverageEdge(0, 257, 0, 1)
}
