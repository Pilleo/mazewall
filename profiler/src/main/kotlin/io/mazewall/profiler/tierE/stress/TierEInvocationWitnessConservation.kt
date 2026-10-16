package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEBenchmarkAuditMapReader.InvocationWitnessCount
import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount
import io.mazewall.profiler.tierE.engine.TierEbpfEngine.EarlyTargetOpenatCounts

/** Unbound target traffic is harmless only when *every* declared ID joined both programs. */
internal object TierEInvocationWitnessConservation {
    private const val FIRST_FAILURE_KEY = 9
    private const val LAST_FAILURE_KEY = 14
    private const val RAW_NO_TOKEN_KEY = 11
    private const val MAIN_NO_TOKEN_KEY = 12
    private val hardFailureKeys = listOf(FIRST_FAILURE_KEY, FIRST_FAILURE_KEY + 1, LAST_FAILURE_KEY - 1, LAST_FAILURE_KEY)

    data class Result(
        val complete: Boolean,
        val ambientRaw: Long?,
        val ambientMain: Long?,
    )

    fun evaluate(
        nativeIds: Map<Int, Long>,
        tokenCounts: Map<Long, InvocationWitnessCount>,
        descriptorStages: Map<Int, TierEPreGateAuditCount>,
        rawObserved: Map<Int, Long>,
        mainSubmitted: Map<Int, Long>,
        early: EarlyTargetOpenatCounts,
        failures: Map<Int, Long>,
    ): Result {
        check(
            nativeIds.isNotEmpty() &&
            failures.keys == (FIRST_FAILURE_KEY..LAST_FAILURE_KEY).toSet() &&
            nativeIds.keys == descriptorStages.keys &&
            nativeIds.keys == rawObserved.keys &&
            nativeIds.keys == mainSubmitted.keys,
        ) { "invocation witness conservation lacks complete original descriptor or armed failure evidence" }
        val expected = nativeIds.size.toLong()
        val rawSurplus = early.raw - expected
        val mainSurplus = early.main - expected
        val complete = allDeclared(nativeIds, tokenCounts, descriptorStages, rawObserved, mainSubmitted) &&
            tokenCounts.keys == nativeIds.values.toSet() &&
            rawSurplus >= 0 &&
            mainSurplus >= 0 &&
            failures.getValue(RAW_NO_TOKEN_KEY) == rawSurplus &&
            failures.getValue(MAIN_NO_TOKEN_KEY) == mainSurplus &&
            hardFailureKeys.all { failures.getValue(it) == 0L }
        return if (complete) Result(true, rawSurplus, mainSurplus) else Result(false, null, null)
    }

    private fun allDeclared(
        nativeIds: Map<Int, Long>,
        tokenCounts: Map<Long, InvocationWitnessCount>,
        descriptorStages: Map<Int, TierEPreGateAuditCount>,
        rawObserved: Map<Int, Long>,
        mainSubmitted: Map<Int, Long>,
    ): Boolean =
        nativeIds.all { (fd, id) ->
            val counts = descriptorStages.getValue(fd)
            tokenCounts[id] == InvocationWitnessCount(1, 1) &&
                counts.declared == 1L &&
                counts.rawBeforeSession == 1L &&
                counts.mainBeforeSession == 1L &&
                counts.rawAfterSession == 1L &&
                counts.mainAfterSession == 1L &&
                rawObserved.getValue(fd) == 1L &&
                mainSubmitted.getValue(fd) == 1L
        }
}
