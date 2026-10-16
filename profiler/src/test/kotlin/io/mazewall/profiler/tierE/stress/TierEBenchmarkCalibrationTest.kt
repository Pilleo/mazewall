package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierEBenchmarkCalibrationTest {
    @Test
    fun `selects one doubled count that reaches the fastest-mode target`() {
        val result = TierEBenchmarkCalibration.select(
            observations = listOf(observation("baseline", batchNanos = 1_000_000), observation("profiled", batchNanos = 10_000_000)),
            targetSessionBatches = 13,
        )

        assertEquals(12_800, result.operations)
        assertTrue(result.targetMet)
        assertEquals("met", result.status)
    }

    @Test
    fun `slowest mode cap wins and makes the target explicitly unmet`() {
        val result = TierEBenchmarkCalibration.select(
            observations = listOf(observation("baseline", batchNanos = 100_000), observation("slow", batchNanos = 10_000_000_000)),
            targetSessionBatches = 13,
        )

        assertEquals(100, result.operations)
        assertFalse(result.targetMet)
        assertEquals("unmet", result.status)
    }

    @Test
    fun `retention cap accounts for the complete target session`() {
        val result = TierEBenchmarkCalibration.select(
            observations = listOf(
                observation("baseline", batchNanos = 1_000_000),
                observation("profiled", batchNanos = 2_000_000, retainedBytes = 24L * 1024 * 1024),
            ),
            targetSessionBatches = 13,
        )

        assertEquals(100, result.maximumRetentionOperations)
        assertEquals(100, result.operations)
        assertFalse(result.targetMet)
    }

    @Test
    fun `record ceiling caps a full-stream calibration before collector loss`() {
        val result = TierEBenchmarkCalibration.select(
            observations = listOf(
                observation("baseline", batchNanos = 1_000_000),
                observation("profiled", batchNanos = 10_000_000, retainedRecords = 1_800),
            ),
            targetSessionBatches = 6,
        )

        assertEquals(3_200, result.maximumRetainedRecordOperations)
        assertEquals(3_200, result.operations)
        assertFalse(result.targetMet)
    }

    @Test
    fun `verification backs down through calibrated candidates`() {
        val attempted = mutableListOf<Int>()

        val selected = TierEBenchmarkCalibration.firstSafeAtOrBelow(12_800) { operations ->
            attempted += operations
            operations <= 3_200
        }

        assertEquals(3_200, selected)
        assertEquals(listOf(12_800, 6_400, 3_200), attempted)
    }

    @Test
    fun `operation candidates remain bounded and reversible`() {
        assertEquals(51_200, TierEBenchmarkCalibration.previous(65_536))
        assertEquals(100, TierEBenchmarkCalibration.previous(100))
    }

    private fun observation(
        variant: String,
        batchNanos: Long,
        retainedBytes: Long = 0,
        retainedRecords: Long = 0,
    ) = TierEBenchmarkCalibration.Observation(
        variant = variant,
        operations = 100,
        measuredBatchNanos = batchNanos,
        peakRetainedBytes = retainedBytes,
        retainedRecords = retainedRecords,
        totalSessionBatches = 6,
    )
}
