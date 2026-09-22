package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.EvidenceIntegrity
import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.TierECollectorRuntimeResult
import io.mazewall.profiler.internal.attribution.TierEProfile

/** Platform-neutral terminal projection; Tier E evidence is never policy-eligible. */
internal data class TierETerminalReport(
    val evidenceIntegrity: EvidenceIntegrity,
    val terminalReason: String,
    val resolvedSyscalls: Int,
    val captureDomainValidity: Boolean,
    val transportCompleteness: Boolean,
    val attributionCoverage: Boolean,
    val dictionaryIntegrity: Boolean,
    val lifecycleValidity: Boolean,
    val registrationId: String? = null,
    val sessionEpoch: Long? = null,
    val targetPid: Int? = null,
    val processIncarnation: Long? = null,
    val artifactByteLength: Long? = null,
    val artifactSha256: String? = null,
    val policyEligibility: Boolean = false,
) {
    companion object {
        fun from(
            result: TierECollectorRuntimeResult,
            target: TierERegisteredTarget? = null,
            contents: AgentDefinitionFile.Contents? = null,
        ): TierETerminalReport {
            val dimensions = result.profile.integrity.verdictDimensions
            return TierETerminalReport(
                evidenceIntegrity = result.profile.integrity.evidenceIntegrity,
                terminalReason = result.terminalReason.name,
                resolvedSyscalls = resolvedCount(result.profile),
                captureDomainValidity = dimensions.captureDomainValid,
                transportCompleteness = dimensions.transportComplete,
                attributionCoverage = dimensions.attributionCoverageValid,
                dictionaryIntegrity = dimensions.dictionaryIntegrityValid,
                lifecycleValidity = dimensions.lifecycleValid,
                registrationId = target?.registrationId,
                sessionEpoch = target?.sessionEpoch?.value,
                targetPid = target?.pid,
                processIncarnation = target?.processIncarnation,
                artifactByteLength = contents?.sourceByteLength,
                artifactSha256 = contents?.sourceSha256,
            )
        }

        internal fun resolvedCount(profile: TierEProfile): Int = profile.syscalls.count { it.resolutionStatus == ResolutionStatus.RESOLVED }
    }
}
