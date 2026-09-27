package io.mazewall.profiler.tierE.stress

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

    fun render(
        enabled: Boolean,
        counts: Map<Int, TierEbpfEngine.ExactAdmissionAuditCount>,
        early: TierEbpfEngine.EarlyTargetOpenatCounts? = null,
    ): String {
        require(enabled || early == null) { "early target comparison requires the exact admission audit" }
        val missing = counts.filterValues { it.low32HandlerEntered != 1L }
        val zeroFirst = missing.entries.sortedWith(compareBy({ it.value.low32HandlerEntered != 0L }, { it.key }))
        val version = if (early != null) {
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
            "exact_admission_audit_low32_handler_entered=${counts.values.sumOf { it.low32HandlerEntered }} " +
            "exact_admission_audit_low32_missing_fd_samples=${zeroFirst.take(MAX_SAMPLES)
                .joinToString(",") { (fd, count) -> "$fd:${count.low32HandlerEntered}" }.ifEmpty { "none" }}"
    }
}
