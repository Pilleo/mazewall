package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.AttributionStatus
import io.mazewall.profiler.internal.attribution.EvidenceIntegrity
import io.mazewall.profiler.internal.attribution.InvocationId
import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.ResolvedSyscall
import io.mazewall.profiler.internal.attribution.SessionIntegrity
import io.mazewall.profiler.internal.attribution.SyscallAttribution
import io.mazewall.profiler.internal.attribution.TaskIdentity
import io.mazewall.profiler.internal.attribution.TierEProfile
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TierETerminalReportFileSinkTest {
    @Test
    fun `resolved count excludes unresolved retained observations`() {
        val observation = SyscallAttribution(
            TaskIdentity(1, 2, 3),
            4,
            257,
            List(6) { 0L },
            InvocationId(5),
            AttributionStatus.PENDING_DICTIONARY,
        )
        val profile = TierEProfile(
            listOf(
                ResolvedSyscall(observation, null, ResolutionStatus.RESOLVED),
                ResolvedSyscall(observation, null, ResolutionStatus.DICTIONARY_UNRESOLVED),
            ),
            SessionIntegrity(),
        )

        assertEquals(1, TierETerminalReport.resolvedCount(profile))
    }

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

    @Test
    fun `incomplete report names every failed verdict dimension`() {
        val root = Files.createTempDirectory("tier-e-report")

        TierETerminalReportFileSink(root).accept(
            report("orders").copy(
                evidenceIntegrity = EvidenceIntegrity.INCOMPLETE,
                terminalReason = "MISSING_TERMINAL_ARTIFACT",
                captureDomainValidity = false,
                transportCompleteness = false,
                lifecycleValidity = false,
            ),
        )

        val json = root.resolve("orders-19802.tier-e.json").readText()
        assertTrue(json.contains("\"failed_dimensions\":[\"capture_domain_validity\",\"transport_completeness\",\"lifecycle_validity\"]"))
        assertTrue(json.contains("\"policy_eligibility\":false"))
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
