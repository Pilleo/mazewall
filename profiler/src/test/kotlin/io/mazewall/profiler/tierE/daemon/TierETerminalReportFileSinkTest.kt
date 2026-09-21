package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.EvidenceIntegrity
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TierETerminalReportFileSinkTest {
    @Test
    fun `writes one atomically named terminal JSON report`() {
        val root = Files.createTempDirectory("tier-e-report")
        val sink = TierETerminalReportFileSink(root)

        sink.accept(report("orders"))

        val report = root.resolve("orders-19802.tier-e.json")
        assertTrue(Files.isRegularFile(report))
        assertTrue(report.readText().contains("\"policy_eligibility\":false"))
        assertTrue(Files.list(root).use { files -> files.count() == 1L })
    }

    @Test
    fun `rejects unsafe registration id before resolving output path`() {
        val root = Files.createTempDirectory("tier-e-report")

        assertFailsWith<IllegalArgumentException> {
            TierETerminalReportFileSink(root).accept(report("../escape"))
        }
    }

    @Test
    fun `rejects an evidence root that is not a directory`() {
        val file = Files.createTempFile("tier-e-report", ".json")

        assertFailsWith<IllegalArgumentException> {
            TierETerminalReportFileSink(file)
        }
    }

    private fun report(registrationId: String) =
        TierETerminalReport(
        evidenceIntegrity = EvidenceIntegrity.COMPLETE,
        terminalReason = "COMPLETE",
        resolvedSyscalls = 1,
        captureDomainValidity = true,
        transportCompleteness = true,
        attributionCoverage = true,
        dictionaryIntegrity = true,
        lifecycleValidity = true,
        registrationId = registrationId,
        sessionEpoch = 19_802L,
    )
}
