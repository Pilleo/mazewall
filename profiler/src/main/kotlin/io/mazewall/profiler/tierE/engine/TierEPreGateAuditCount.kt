package io.mazewall.profiler.tierE.engine

/** Benchmark-only descriptor array value; all slots match aligned Linux u64 fields. */
internal data class TierEPreGateAuditCount(
    val declared: Long,
    val rawBeforeSession: Long,
    val mainBeforeSession: Long,
    val rawAfterSession: Long,
    val mainAfterSession: Long,
) {
    internal companion object {
        const val DECLARED_OFFSET = 0
        const val RAW_BEFORE_OFFSET = 8
        const val MAIN_BEFORE_OFFSET = 16
        const val RAW_AFTER_OFFSET = 24
        const val MAIN_AFTER_OFFSET = 32
        const val VALUE_SIZE = 40
    }
}
