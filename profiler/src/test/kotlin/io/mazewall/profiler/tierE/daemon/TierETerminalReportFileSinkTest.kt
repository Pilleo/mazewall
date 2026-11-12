package io.mazewall.profiler.tierE.daemon

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
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

@EnabledIfLinuxAndSupported
class TierETerminalReportFileSinkTest {
    @Test
    fun `default and selected access apply after complete terminal write`() {
        for (selected in listOf(false, true)) {
            val root = Files.createTempDirectory("tier-e-report-access-")
            try {
                val gid = Files.getAttribute(root, "unix:gid") as Int
                val selectedGid = selectedEvidenceTestGid(root)
                val access = TierEEvidenceFileAccess(if (selected) selectedGid else null)
                TierETerminalReportFileSink(root, access, prepareTemporary = { file ->
                    assertEquals(report("orders").toJson(), Files.readString(file))
                    access.apply(file)
                }).accept(report("orders"))
                val file = root.resolve("orders-19802.tier-e.json")
                assertEquals(if (selected) selectedGid else gid, Files.getAttribute(file, "unix:gid"))
                assertEquals(
                    if (selected) "rw-r-----" else "rw-------",
                    java.nio.file.attribute.PosixFilePermissions
                    .toString(Files.getPosixFilePermissions(file)),
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `group mode and atomic publication failures preserve existing final or absence`() {
        for (existing in listOf(false, true)) {
            for (fault in listOf("group", "mode", "publication")) {
                val root = Files.createTempDirectory("tier-e-report-fault-")
                try {
                    val final = root.resolve("orders-19802.tier-e.json")
                    if (existing) Files.writeString(final, "original")
                    val access = TierEEvidenceFileAccess(Files.getAttribute(root, "unix:gid") as Int)
                    val failure = java.io.IOException(fault)
                    val sink = TierETerminalReportFileSink(root, prepareTemporary = { file ->
                        when (fault) {
                            "group" -> access.apply(file, setGroup = { _, _ -> throw failure })
                            "mode" -> access.apply(file, setMode = { _, _ -> throw failure })
                            else -> access.apply(file)
                        }
                    }, replaceTemporary = { _, _ -> throw failure })
                    assertEquals(failure, assertFailsWith<java.io.IOException> { sink.accept(report("orders")) })
                    assertEquals(existing, Files.exists(final))
                    if (existing) assertEquals("original", Files.readString(final))
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `terminal replaces a symlink itself without touching its referent`() {
        val root = Files.createTempDirectory("tier-e-report-symlink-")
        try {
            val sentinel = Files.writeString(root.resolve("sentinel"), "untouched")
            val final = root.resolve("orders-19802.tier-e.json")
            Files.createSymbolicLink(final, sentinel)
            TierETerminalReportFileSink(root).accept(report("orders"))
            assertEquals(false, Files.isSymbolicLink(final))
            assertEquals(report("orders").toJson(), Files.readString(final))
            assertEquals("untouched", Files.readString(sentinel))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unsupported atomic replacement never falls back and keeps existing bytes`() {
        val root = Files.createTempDirectory("tier-e-atomic-unsupported-")
        try {
            val final = Files.writeString(root.resolve("orders-19802.tier-e.json"), "original")
            val failure = java.nio.file.AtomicMoveNotSupportedException("temporary", final.toString(), "injected")
            val sink = TierETerminalReportFileSink(root, replaceTemporary = { _, _ -> throw failure })
            assertEquals(failure, assertFailsWith<java.nio.file.AtomicMoveNotSupportedException> { sink.accept(report("orders")) })
            assertEquals("original", Files.readString(final))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

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

    @Test
    fun `terminal report carries additive recovery evidence without touching ring verdicts`() {
        val root = Files.createTempDirectory("tier-e-report")

        TierETerminalReportFileSink(root).accept(
            report("orders").copy(
                recoveryEvidence = io.mazewall.profiler.internal.attribution.CoverageRecoveryEvidence(
                    mapCapacity = 4096,
                    recoveredEdges = 1,
                    mapFirstAcknowledgedEdges = 1,
                ),
            ),
        )

        val json = root.resolve("orders-19802.tier-e.json").readText()
        assertTrue(json.contains("\"recovery_evidence\":{\"map_capacity\":4096"))
        assertTrue(json.contains("\"recovered_edges\":1"))
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
