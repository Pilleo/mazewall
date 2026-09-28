package io.mazewall.profiler.tierE.stress

/** Preserve the target's independent operation denominator in either comparison arm. */
internal object TierESustainedComparisonMode {
    data class Selection(
        val invocationWitness: Boolean,
        val comparison: Boolean,
    )

    fun select(
        scope: String,
        rawAudit: Boolean,
        exactAudit: Boolean,
        witnessFlag: String,
        comparisonFlag: String,
    ): Selection {
        val witness = witnessFlag.toBooleanStrictOrNull() ?: error("invocation-ID witness must be true or false")
        val comparison = comparisonFlag.toBooleanStrictOrNull() ?: error("sustained comparison must be true or false")
        val sustained = scope == "tier-e-sustained-collector"
        require(!witness || (sustained && rawAudit && exactAudit)) {
            "invocation-ID witness requires paired sustained benchmark raw/main audit"
        }
        require(!comparison || (sustained && ((rawAudit && exactAudit && witness) || (!rawAudit && !exactAudit && !witness)))) {
            "sustained comparison requires either current paired witness or both audits disabled"
        }
        return Selection(witness, comparison)
    }

    fun exportDeclaredManifest(
        comparison: Boolean,
        sustainedSharedBenchmark: Boolean,
        pairedAudit: Boolean,
    ): Boolean {
        check(!comparison || sustainedSharedBenchmark) { "operation comparison requires the sustained shared-openat benchmark" }
        return comparison || pairedAudit
    }
}
