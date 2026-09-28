package io.mazewall.profiler.tierE.stress

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEInvocationWitnessCpuEvidenceTest {
    private val valid = "tier-e-pre-gate-affinity: workload_cpu=0 threads=13 launched_pinned=true\n" +
        "tier-e-kernel-smoke: raw_sys_enter_audit_cpu_counts=0:10000 " +
        "raw_sys_enter_audit_cpu_total=10000 raw_sys_enter_audit_observed_operations=10000\n"

    @Test
    fun `confirmed startup affinity and all raw events stay on selected workload core`() {
        TierEInvocationWitnessCpuEvidence.requireComplete(valid, "0", 10000)
    }

    @Test
    fun `even one raw event outside workload CPU halts before the next fork`() {
        assertThrows(IllegalStateException::class.java) {
            TierEInvocationWitnessCpuEvidence.requireComplete(valid.replace("0:10000", "0:9999,2:1"), "0", 10000)
        }
        assertThrows(IllegalStateException::class.java) {
            TierEInvocationWitnessCpuEvidence.requireComplete(valid.replace("threads=13", "threads=0"), "0", 10000)
        }
    }
}
