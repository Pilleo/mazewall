package io.mazewall.profiler.tierE.daemon

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/** Atomically stores a platform-neutral terminal report in an operator-owned evidence root. */
internal class TierETerminalReportFileSink(
    root: Path,
) : TierETerminalReportSink {
    private val root = root.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            "Tier E evidence root must be an existing directory"
        }
        require(!Files.isSymbolicLink(this.root)) { "Tier E evidence root must not be a symbolic link" }
    }

    override fun accept(report: TierETerminalReport) {
        val registrationId = requireNotNull(report.registrationId) { "terminal report lacks registration identity" }
        val sessionEpoch = requireNotNull(report.sessionEpoch) { "terminal report lacks session epoch" }
        require(registrationId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))) {
            "terminal report registration id is invalid"
        }
        val file = root.resolve("$registrationId-$sessionEpoch.tier-e.json").normalize()
        require(file.parent == root) { "terminal report escaped evidence root" }
        val temporary = Files.createTempFile(root, ".$registrationId-$sessionEpoch.", ".tmp")
        try {
            Files.newByteChannel(temporary, setOf(TRUNCATE_EXISTING, WRITE)).use { channel ->
                channel.write(StandardCharsets.UTF_8.encode(report.toJson()))
            }
            try {
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

internal fun TierETerminalReport.toJson(): String {
    val artifactLength = artifactByteLength?.toString() ?: "null"
    val artifactDigest = artifactSha256?.let { "\"$it\"" } ?: "null"
    val failedDimensions = buildList {
        if (!captureDomainValidity) add("capture_domain_validity")
        if (!transportCompleteness) add("transport_completeness")
        if (!attributionCoverage) add("attribution_coverage")
        if (!dictionaryIntegrity) add("dictionary_integrity")
        if (!lifecycleValidity) add("lifecycle_validity")
    }.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
    return """{"schema_version":1,"registration_id":"$registrationId","session_epoch":$sessionEpoch,""" +
        """"target_pid":$targetPid,"process_incarnation":$processIncarnation,""" +
        """"artifact_byte_length":$artifactLength,"artifact_sha256":$artifactDigest,""" +
        """"evidence_integrity":"$evidenceIntegrity","terminal_reason":"$terminalReason",""" +
        """"resolved_syscalls":$resolvedSyscalls,"capture_domain_validity":$captureDomainValidity,""" +
        """"transport_completeness":$transportCompleteness,"attribution_coverage":$attributionCoverage,""" +
        """"dictionary_integrity":$dictionaryIntegrity,"lifecycle_validity":$lifecycleValidity,""" +
        """"failed_dimensions":$failedDimensions,""" +
        """"recovery_evidence":{"map_capacity":${recoveryEvidence.mapCapacity},""" +
        """"entries_observed":${recoveryEvidence.entriesObserved},""" +
        """"insertion_failures":${recoveryEvidence.insertionFailures},""" +
        """"pending_candidates":${recoveryEvidence.pendingCandidates},""" +
        """"recovered_edges":${recoveryEvidence.recoveredEdges},""" +
        """"acknowledgement_retries":${recoveryEvidence.acknowledgementRetries},""" +
        """"scan_batches":${recoveryEvidence.scanBatches},""" +
        """"overdue_sweeps":${recoveryEvidence.overdueSweeps},""" +
        """"last_scan_latency_ns":${recoveryEvidence.lastScanLatencyNanos},""" +
        """"map_first_acknowledged_edges":${recoveryEvidence.mapFirstAcknowledgedEdges},""" +
        """"confirmed_ring_loss_recoveries":${recoveryEvidence.confirmedRingLossRecoveries},""" +
        """"last_recovery_latency_ns":${recoveryEvidence.lastRecoveryLatencyNanos},""" +
        """"max_recovery_latency_ns":${recoveryEvidence.maxRecoveryLatencyNanos}},""" +
        """"policy_eligibility":false}"""
}
