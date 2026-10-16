package io.mazewall.profiler.hotspot.performance

/** Diagnostic fixture limits, independent of collector deadlines and retention budgets. */
internal data class HotSpotWorkloadMeasurementSettings(
    val operations: Int = DEFAULT_OPERATIONS,
    val warmups: Int = DEFAULT_WARMUPS,
    val paceMillis: Long = DEFAULT_PACE_MILLIS,
) {
    init {
        require(operations in 1..MAX_OPERATIONS)
        require(warmups in 0..MAX_WARMUPS)
        require(paceMillis in 1..DEFAULT_PACE_MILLIS)
    }

    companion object {
        private const val DEFAULT_OPERATIONS = 20
        private const val DEFAULT_WARMUPS = 40
        private const val DEFAULT_PACE_MILLIS = 100L
        private const val MAX_OPERATIONS = 600
        private const val MAX_WARMUPS = 400
    }
}
