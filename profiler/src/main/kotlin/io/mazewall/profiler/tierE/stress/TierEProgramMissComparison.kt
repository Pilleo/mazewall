package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEbpfEngine.BpfProgramExecutionEvidence

/** Program-wide recursion misses within one shared-arm interval; never per-ID attribution. */
internal object TierEProgramMissComparison {
    data class Delta(
        val main: Long?,
        val raw: Long?,
    )

    fun compare(
        before: Map<String, BpfProgramExecutionEvidence>,
        after: Map<String, BpfProgramExecutionEvidence>,
    ): Delta =
        Delta(
        main = subtract(before.getValue("main"), after.getValue("main")),
        raw = subtract(before.getValue("audit"), after.getValue("audit")),
    )

    private data class Snapshot(
        val programId: Int,
        val translatedBytes: Int,
        val misses: Long?,
    )

    fun sample(evidence: BpfProgramExecutionEvidence): Long? = snapshot(evidence).misses

    private fun snapshot(evidence: BpfProgramExecutionEvidence): Snapshot =
        when (evidence) {
        is BpfProgramExecutionEvidence.Available -> Snapshot(evidence.programId, evidence.translatedInstructionBytes, evidence.recursionMisses)
        is BpfProgramExecutionEvidence.IdentityOnly -> Snapshot(evidence.programId, evidence.translatedInstructionBytes, evidence.recursionMisses)
        is BpfProgramExecutionEvidence.Unavailable, BpfProgramExecutionEvidence.NotAttached ->
            error("Tier E recursion comparison requires an identified attached BPF program")
    }

    private fun subtract(
        before: BpfProgramExecutionEvidence,
        after: BpfProgramExecutionEvidence,
    ): Long? {
        val first = snapshot(before)
        val last = snapshot(after)
        check(first.programId > 0 && first.programId == last.programId && first.translatedBytes == last.translatedBytes) {
            "Tier E recursion-miss comparison changed installed BPF program identity"
        }
        val early = first.misses ?: return null
        val later = last.misses ?: return null
        check(later >= early) { "Tier E program recursion-miss counter decreased" }
        return later - early
    }
}
