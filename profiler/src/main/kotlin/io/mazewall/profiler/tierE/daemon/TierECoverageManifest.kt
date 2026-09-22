package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.StackCaptureQuality
import io.mazewall.profiler.internal.attribution.TierECollectorRuntimeResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Benchmark-only collector-owned coverage projection.
 *
 * It is deliberately separate from terminal evidence: this manifest is a fixture denominator
 * comparison aid, never a policy input and never a live collector data structure.
 */
internal data class TierECoverageManifest(
    val registrationId: String,
    val sessionEpoch: Long,
    val expectedOracleSha256: String,
    val terminalReportSha256: String,
    val evidenceIntegrity: String,
    val captureDomainValidity: Boolean,
    val transportCompleteness: Boolean,
    val attributionCoverage: Boolean,
    val dictionaryIntegrity: Boolean,
    val lifecycleValidity: Boolean,
    val edges: List<TierECoverageEdge>,
) {
    init {
        require(registrationId.matches(REGISTRATION_ID)) { "coverage manifest registration id is invalid" }
        require(sessionEpoch >= 0L) { "coverage manifest session epoch must be non-negative" }
        require(expectedOracleSha256.matches(SHA256)) { "coverage manifest oracle checksum is invalid" }
        require(terminalReportSha256.matches(SHA256)) { "coverage manifest terminal checksum is invalid" }
        require(edges.size <= MAX_EDGES) { "coverage manifest exceeds edge budget" }
        require(edges.zipWithNext().all { (left, right) -> left.key < right.key }) {
            "coverage manifest edges must be unique and ordered"
        }
    }

    fun toJson(): String =
        buildString {
        append("{\"schema_version\":1,\"registration_id\":\"").append(registrationId)
        append("\",\"session_epoch\":").append(sessionEpoch)
        append(",\"expected_oracle_sha256\":\"").append(expectedOracleSha256)
        append("\",\"terminal_report_sha256\":\"").append(terminalReportSha256)
        append("\",\"evidence_integrity\":\"").append(evidenceIntegrity)
        append("\",\"capture_domain_validity\":").append(captureDomainValidity)
        append(",\"transport_completeness\":").append(transportCompleteness)
        append(",\"attribution_coverage\":").append(attributionCoverage)
        append(",\"dictionary_integrity\":").append(dictionaryIntegrity)
        append(",\"lifecycle_validity\":").append(lifecycleValidity)
        append(",\"policy_eligibility\":false,\"edges\":[")
        edges.forEachIndexed { index, edge ->
            if (index != 0) append(',')
            append(edge.toJson())
        }
        append("]}")
    }

    companion object {
        private val REGISTRATION_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
        private val SHA256 = Regex("[a-f0-9]{64}")
        internal const val MAX_EDGES = 16_384

        fun from(
            result: TierECollectorRuntimeResult,
            report: TierETerminalReport,
            expectedOracleSha256: String,
            expectedSyscallNumber: Int,
        ): TierECoverageManifest {
            val edges = result.profile.syscalls
                .asSequence()
                .filter { it.resolutionStatus == ResolutionStatus.RESOLVED }
                .filter { it.stackDefinition?.captureQuality == StackCaptureQuality.COMPLETE }
                .filter { it.observation.syscallNumber == expectedSyscallNumber }
                .mapNotNull { syscall ->
                    val definition = requireNotNull(syscall.stackDefinition)
                    val path = definition.frames
                        .asReversed()
                        .firstOrNull { frame ->
                            (frame.className.endsWith("AgentCoverageWorkload") || frame.className.endsWith("TierECoveragePaths")) &&
                                frame.methodName.matches(Regex("path[0-9]{2}"))
                        }?.methodName
                        ?.removePrefix("path")
                        ?.toIntOrNull()
                        ?: return@mapNotNull null
                    TierECoverageEdge(
                        workloadPath = path,
                        syscallNumber = syscall.observation.syscallNumber,
                        captureFlags = syscall.observation.captureFlags,
                        acknowledgedCount = 1,
                    )
                }.groupBy { it.key }
                .map { (_, values) -> values.first().copy(acknowledgedCount = values.size.toLong()) }
                .sortedBy { it.key }
                .toList()
            return TierECoverageManifest(
                registrationId = requireNotNull(report.registrationId),
                sessionEpoch = requireNotNull(report.sessionEpoch),
                expectedOracleSha256 = expectedOracleSha256,
                terminalReportSha256 = sha256(report.jsonBytes()),
                evidenceIntegrity = report.evidenceIntegrity.name,
                captureDomainValidity = report.captureDomainValidity,
                transportCompleteness = report.transportCompleteness,
                attributionCoverage = report.attributionCoverage,
                dictionaryIntegrity = report.dictionaryIntegrity,
                lifecycleValidity = report.lifecycleValidity,
                edges = edges,
            )
        }

        internal fun sha256(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

internal data class TierECoverageEdge(
    val workloadPath: Int,
    val syscallNumber: Int,
    val captureFlags: Int,
    val acknowledgedCount: Long,
) {
    init {
        require(workloadPath in 0..71) { "coverage workload path must be in 0..71" }
        require(acknowledgedCount > 0L) { "coverage acknowledgement count must be positive" }
    }

    val key: String get() = "$workloadPath:$syscallNumber:$captureFlags"

    fun toJson(): String =
        "{\"workload_path\":$workloadPath,\"syscall_number\":$syscallNumber," +
        "\"capture_flags\":$captureFlags,\"acknowledged_count\":$acknowledgedCount}"
}

internal fun TierETerminalReport.jsonBytes(): ByteArray = toJson().toByteArray(StandardCharsets.UTF_8)
