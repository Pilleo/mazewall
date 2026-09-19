package io.mazewall.profiler.tierE.stress

import kotlin.math.ceil

/** Pure selection logic for the benchmark's predeclared shared operation count. */
internal object TierEBenchmarkCalibration {
    const val START_OPERATIONS = 100
    const val MAX_OPERATIONS = 65_536
    const val TARGET_FASTEST_BATCH_NANOS = 100_000_000L
    const val MAX_SLOWEST_BATCH_NANOS = 15_000_000_000L
    const val RETAINED_LIMIT_BYTES = 48L * 1024 * 1024
    const val RETAINED_RECORD_LIMIT = 75_000L

    data class Observation(
        val variant: String,
        val operations: Int,
        val measuredBatchNanos: Long,
        val peakRetainedBytes: Long,
        val retainedRecords: Long,
        val totalSessionBatches: Int,
    ) {
        init {
            require(variant.isNotBlank())
            require(operations > 0 && measuredBatchNanos > 0 && totalSessionBatches > 0)
            require(peakRetainedBytes >= 0)
            require(retainedRecords >= 0)
        }
    }

    data class Selection(
        val operations: Int,
        val targetOperations: Int,
        val maximumSlowModeOperations: Int,
        val maximumRetentionOperations: Int,
        val maximumRetainedRecordOperations: Int,
        val targetMet: Boolean,
        val status: String,
    )

    fun select(
        observations: List<Observation>,
        targetSessionBatches: Int,
    ): Selection {
        require(observations.isNotEmpty())
        require(targetSessionBatches > 0)
        val candidates = candidates()
        val fastestPerOperation = observations.minOf { it.measuredBatchNanos.toDouble() / it.operations }
        val slowestPerOperation = observations.maxOf { it.measuredBatchNanos.toDouble() / it.operations }
        val targetOperations = firstCandidateAtLeast(ceil(TARGET_FASTEST_BATCH_NANOS / fastestPerOperation).toInt())
        val maximumSlow = lastCandidateAtMost((MAX_SLOWEST_BATCH_NANOS / slowestPerOperation).toInt())
        val retainedPerOperation = observations
            .filter { it.peakRetainedBytes > 0 }
            .maxOfOrNull { it.peakRetainedBytes.toDouble() / (it.operations.toLong() * it.totalSessionBatches) }
        val maximumRetention = retainedPerOperation?.let {
            lastCandidateAtMost((RETAINED_LIMIT_BYTES / (it * targetSessionBatches)).toInt())
        } ?: MAX_OPERATIONS
        val retainedRecordsPerOperation = observations
            .filter { it.retainedRecords > 0 }
            .maxOfOrNull { it.retainedRecords.toDouble() / (it.operations.toLong() * it.totalSessionBatches) }
        val maximumRetainedRecords = retainedRecordsPerOperation?.let {
            lastCandidateAtMost((RETAINED_RECORD_LIMIT / (it * targetSessionBatches)).toInt())
        } ?: MAX_OPERATIONS
        val selected = candidates.lastOrNull {
            it <= minOf(targetOperations, maximumSlow, maximumRetention, maximumRetainedRecords)
        }
            ?: START_OPERATIONS
        val met = selected >= targetOperations
        return Selection(
            operations = selected,
            targetOperations = targetOperations,
            maximumSlowModeOperations = maximumSlow,
            maximumRetentionOperations = maximumRetention,
            maximumRetainedRecordOperations = maximumRetainedRecords,
            targetMet = met,
            status = if (met) "met" else "unmet",
        )
    }

    fun previous(operations: Int): Int = candidates().lastOrNull { it < operations } ?: START_OPERATIONS

    fun firstSafeAtOrBelow(
        initialOperations: Int,
        isSafe: (Int) -> Boolean,
    ): Int {
        require(initialOperations in candidates()) { "initial operation count must be a calibration candidate" }
        var operations = initialOperations
        while (!isSafe(operations)) {
            check(operations > START_OPERATIONS) { "minimum calibration workload is unsafe" }
            operations = previous(operations)
        }
        return operations
    }

    private fun firstCandidateAtLeast(value: Int): Int = candidates().firstOrNull { it >= value } ?: MAX_OPERATIONS

    private fun lastCandidateAtMost(value: Int): Int = candidates().lastOrNull { it <= value } ?: START_OPERATIONS

    private fun candidates(): List<Int> =
        buildList {
        var candidate = START_OPERATIONS
        while (candidate < MAX_OPERATIONS) {
            add(candidate)
            candidate *= 2
        }
        if (last() != MAX_OPERATIONS) add(MAX_OPERATIONS)
    }
}
