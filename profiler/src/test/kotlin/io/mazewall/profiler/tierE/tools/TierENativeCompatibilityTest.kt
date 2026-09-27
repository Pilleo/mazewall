package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierENativeCompatibilityTest {
    @Test
    fun `native cache overloads retain existing developer tooling JVM entrypoints`() {
        for ((type, name, parameters) in listOf(
            Triple(TierETrustworthyRunner::class.java, "run", 6),
            Triple(TierENodeNoiseProbe::class.java, "run", 9),
            Triple(TierEProfileExperimentRunner::class.java, "run", 4),
            Triple(TierERootfulPhaseProbe::class.java, "run", 3),
            Triple(TierERootfulGateRunner::class.java, "run", 6),
        )) {
            assertTrue(
                type.methods.any { it.name == name && it.parameterCount == parameters },
                "Cache integration must not remove $name/$parameters from ${type.simpleName}",
            )
        }
    }
}
