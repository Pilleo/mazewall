package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEbpfEngine

/** Versioned, bounded benchmark-only map checkpoint; not attribution evidence. */
internal object TierEAdmissionAuditSummary {
    private const val CONTRACT_VERSION = 2
    private const val MAX_SAMPLES = 16

    fun render(
        enabled: Boolean,
        counts: Map<Int, TierEbpfEngine.ExactAdmissionAuditCount>,
    ): String {
        val missing = counts.filterValues { it.low32HandlerEntered != 1L }
        return "exact_admission_audit_contract_version=${if (enabled) CONTRACT_VERSION else 0} " +
            "exact_admission_audit_expected_operations=${counts.size} " +
            "exact_admission_audit_low32_handler_entered=${counts.values.sumOf { it.low32HandlerEntered }} " +
            "exact_admission_audit_low32_missing_fd_samples=${missing.toSortedMap().entries.take(MAX_SAMPLES)
                .joinToString(",") { (fd, count) -> "$fd:${count.low32HandlerEntered}" }.ifEmpty { "none" }}"
    }
}
