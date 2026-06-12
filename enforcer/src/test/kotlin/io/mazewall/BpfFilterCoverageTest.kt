package io.mazewall

import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class BpfFilterCoverageTest {
    @Test
    fun `test BpfFilter build branches`() {
        val arch = Arch.AMD64

        // Test with profilingMode = true
        val profilingFilters = BpfFilter.build(
            arch = arch,
            policy = Policy.PURE_COMPUTE_UNSAFE,
            profilingMode = true,
        )
        assertNotNull(profilingFilters)

        // Test with profilingMode = false
        val enforcementFilters = BpfFilter.build(
            arch = arch,
            policy = Policy.PURE_COMPUTE_UNSAFE,
            profilingMode = false,
        )
        assertNotNull(enforcementFilters)

        // Test with allowNonThreadClone = true
        val clonePolicy = Policy
            .builder()
            .allowNonThreadClone()
            .build()
        val cloneFilters = BpfFilter.build(arch, clonePolicy)
        assertNotNull(cloneFilters)

        // Test with allowUnsafePrctl = true
        val prctlPolicy = Policy
            .builder()
            .allowUnsafePrctl()
            .build()
        val prctlFilters = BpfFilter.build(arch, prctlPolicy)
        assertNotNull(prctlFilters)

        // Test with custom default action
        val errnoPolicy = Policy
            .builder()
            .defaultAction(SeccompAction.ACT_ERRNO)
            .build()
        val errnoFilters = BpfFilter.build(arch, errnoPolicy)
        assertNotNull(errnoFilters)
    }

    @Test
    fun `test BpfFilter build for different architectures`() {
        // Test ARM64 (AARCH64)
        assertNotNull(BpfFilter.build(Arch.AARCH64, Policy.PURE_COMPUTE_UNSAFE))
    }
}
