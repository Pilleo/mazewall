package io.mazewall.profiler.tierE.stress

/** Close the fork boundary only when both thread affinity and actual observed CPU agree. */
internal object TierEInvocationWitnessCpuEvidence {
    fun requireComplete(
        output: String,
        selectedCpu: String,
        expected: Int,
    ) {
        val affinity = output.lineSequence().singleOrNull { it.startsWith("tier-e-pre-gate-affinity: ") }
        val receipt = affinity?.let {
            Regex("tier-e-pre-gate-affinity: workload_cpu=([0-9]+) threads=([1-9][0-9]*) launched_pinned=true").matchEntire(it)
        }
        check(receipt != null && receipt.groupValues[1] == selectedCpu && expected > 0) {
            "invocation-ID witness has no valid pre-gate all-thread CPU affinity receipt"
        }

        fun field(key: String): String =
            Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)").findAll(output).map { it.groupValues[1] }.singleOrNull()
                ?: error("missing or duplicate invocation-ID CPU field $key")

        check(
            field("raw_sys_enter_audit_cpu_counts") == "$selectedCpu:$expected" &&
            field("raw_sys_enter_audit_cpu_total").toIntOrNull() == expected &&
            field("raw_sys_enter_audit_observed_operations").toIntOrNull() == expected,
        ) { "invocation-ID witness target events were not all observed on workload CPU $selectedCpu" }
    }
}
