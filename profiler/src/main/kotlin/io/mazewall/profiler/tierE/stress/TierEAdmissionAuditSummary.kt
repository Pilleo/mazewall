package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount
import io.mazewall.profiler.tierE.engine.TierEbpfEngine

/** Versioned, bounded benchmark-only map checkpoint; not attribution evidence. */
internal object TierEAdmissionAuditSummary {
    private const val CONTRACT_VERSION = 2
    private const val MAX_SAMPLES = 16

    fun captureEarlyTargetCounts(
        engine: TierEbpfEngine,
        enabled: Boolean,
    ): TierEbpfEngine.EarlyTargetOpenatCounts? = if (enabled) engine.readEarlyTargetOpenatCounts() else null

    /** Independent audit completeness is required even if the main operation oracle passed. */
    fun pairedAuditComplete(
        expected: Int,
        rawObserved: Long,
        rawMissing: Int,
        exactMissing: Int,
        rawReadFailures: Long,
        exactReadFailures: Long,
        early: TierEbpfEngine.EarlyTargetOpenatCounts,
    ): Boolean =
        expected > 0 &&
        rawObserved == expected.toLong() &&
        rawMissing == 0 &&
        exactMissing == 0 &&
        rawReadFailures == 0L &&
        exactReadFailures == 0L &&
        early.raw == early.main &&
        early.raw >= expected

    /** The earliest unequal per-ID boundary; no aggregate ambient lookup is attributed to an ID. */
    fun preGateSummary(
        expectedDirectoryFds: Set<Int>,
        counts: Map<Int, TierEPreGateAuditCount>,
    ): String {
        if (expectedDirectoryFds.isEmpty() || counts.keys != expectedDirectoryFds) return "INCONCLUSIVE"
        return counts
            .toSortedMap()
            .entries
            .mapNotNull { (fd, value) -> classifyPreGate(value)?.let { "$fd:$it" } }
            .take(MAX_SAMPLES)
            .joinToString(",")
            .ifEmpty { "PASS" }
    }

    private fun classifyPreGate(value: TierEPreGateAuditCount): String? =
        when {
        value.declared != 1L -> "INCONCLUSIVE"
        hasInvalidStageOrder(value) -> "INCONCLUSIVE"
        value.rawBeforeSession !in 0L..1L || value.mainBeforeSession !in 0L..1L -> "INCONCLUSIVE"
        value.rawBeforeSession == 1L && value.mainBeforeSession == 0L -> "MAIN_PRE_STAGE_ABSENT"
        value.rawBeforeSession == 0L && value.mainBeforeSession == 1L -> "RAW_PRE_STAGE_ABSENT"
        value.rawBeforeSession == 1L &&
            value.mainBeforeSession == 1L &&
            value.rawAfterSession == 1L &&
                value.mainAfterSession == 0L -> "MAIN_POST_STAGE_ABSENT"
            hasBothPostSessionCounts(value) -> null
            else -> "INCONCLUSIVE"
        }

    private fun hasBothPostSessionCounts(value: TierEPreGateAuditCount): Boolean =
        value.rawBeforeSession == 1L &&
            value.mainBeforeSession == 1L &&
            value.rawAfterSession == 1L &&
        value.mainAfterSession == 1L

    private fun hasInvalidStageOrder(value: TierEPreGateAuditCount): Boolean = value.rawAfterSession > value.rawBeforeSession || value.mainAfterSession > value.mainBeforeSession

    fun render(
        enabled: Boolean,
        counts: Map<Int, TierEbpfEngine.ExactAdmissionAuditCount>,
        early: TierEbpfEngine.EarlyTargetOpenatCounts? = null,
        preGate: Map<Int, TierEPreGateAuditCount>? = null,
        versionFive: Boolean = false,
    ): String {
        require(enabled || early == null) { "early target comparison requires the exact admission audit" }
        require(preGate == null || early != null) { "pre-gate descriptor report requires an armed early target comparison" }
        require(!versionFive || preGate != null) { "version-five diagnostic requires declared per-ID evidence" }
        val missing = counts.filterValues { it.low32HandlerEntered != 1L }
        val zeroFirst = missing.entries.sortedWith(compareBy({ it.value.low32HandlerEntered != 0L }, { it.key }))
        val version = if (versionFive) {
            CONTRACT_VERSION + 3
        } else if (preGate != null) {
            CONTRACT_VERSION + 2
        } else if (early != null) {
            CONTRACT_VERSION + 1
        } else if (enabled) {
            CONTRACT_VERSION
        } else {
            0
        }
        return "exact_admission_audit_contract_version=$version " +
            "exact_admission_audit_expected_operations=${counts.size} " +
            early
                ?.let {
                "raw_sys_enter_audit_pre_decode_target_openat=${it.raw} " +
                    "exact_admission_audit_pre_decode_target_openat=${it.main} "
            }.orEmpty() +
            preGate
                ?.let { values ->
                "pre_gate_audit_raw_before_session=${values.values.sumOf { it.rawBeforeSession }} " +
                    "pre_gate_audit_main_before_session=${values.values.sumOf { it.mainBeforeSession }} " +
                    "pre_gate_audit_raw_after_session=${values.values.sumOf { it.rawAfterSession }} " +
                    "pre_gate_audit_main_after_session=${values.values.sumOf { it.mainAfterSession }} "
            }.orEmpty() +
            "exact_admission_audit_low32_handler_entered=${counts.values.sumOf { it.low32HandlerEntered }} " +
            "exact_admission_audit_low32_missing_fd_samples=${zeroFirst.take(MAX_SAMPLES)
                .joinToString(",") { (fd, count) -> "$fd:${count.low32HandlerEntered}" }.ifEmpty { "none" }}"
    }
}
