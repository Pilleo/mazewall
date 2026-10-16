package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEBenchmarkAuditMapReader.InvocationWitnessCount
import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount

/** A token checkpoint proves entry into that BPF program, not why a missing token failed to run. */
internal object TierEInvocationWitnessComparison {
    fun classify(
        nativeIds: Map<Int, Long>,
        tokens: Map<Long, InvocationWitnessCount>,
        descriptorStages: Map<Int, TierEPreGateAuditCount>,
    ): Map<Int, String> {
        check(nativeIds.isNotEmpty() && nativeIds.keys == descriptorStages.keys && nativeIds.values.toSet().size == nativeIds.size) {
            "native invocation witness does not bind the complete declared descriptor set"
        }
        return nativeIds.mapValues { (fd, id) ->
            val token = tokens[id] ?: InvocationWitnessCount(0, 0)
            val stages = descriptorStages.getValue(fd)
            validateStages(fd, token, stages)
            val raw = stages.rawBeforeSession
            val main = stages.mainBeforeSession
            when {
                token.raw == 0L && token.main == 0L -> "BOTH_BEFORE_TOKEN"
                token.raw == 0L -> "RAW_BEFORE_TOKEN"
                token.main == 0L -> "MAIN_BEFORE_TOKEN"
                raw == 0L -> "RAW_AFTER_TOKEN"
                main == 0L -> "MAIN_AFTER_TOKEN"
                else -> "FD_CHECKPOINT_PRESENT"
            }
        }
    }

    private fun validateStages(
        fd: Int,
        token: InvocationWitnessCount,
        stages: TierEPreGateAuditCount,
    ) {
        val raw = stages.rawBeforeSession
        val main = stages.mainBeforeSession
            check(
                stages.declared == 1L &&
                token.raw in 0..1 &&
                token.main in 0..1 &&
                raw in 0..1 &&
                main in 0..1 &&
                raw <= token.raw &&
                main <= token.main &&
                stages.rawAfterSession in 0..raw &&
                stages.mainAfterSession in 0..main,
            ) { "invocation witness contradicts established raw/main FD checkpoints at fd=$fd" }
    }
}
