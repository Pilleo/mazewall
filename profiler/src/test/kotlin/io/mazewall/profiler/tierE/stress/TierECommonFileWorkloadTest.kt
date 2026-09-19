package io.mazewall.profiler.tierE.stress

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierECommonFileWorkloadTest {
    @Test
    fun `mixed profiles retain a deterministic CPU schedule around the same syscall manifest`() {
        assertEquals(0, TierESharedOpenatOracleWorkload.Profile.SYSCALL_DENSE.cpuRounds(0))
        assertEquals(256, TierESharedOpenatOracleWorkload.Profile.MIXED_CPU_IO.cpuRounds(0))
        assertEquals(0, TierESharedOpenatOracleWorkload.Profile.BURSTY_IO.cpuRounds(30))
        assertEquals(8_192, TierESharedOpenatOracleWorkload.Profile.BURSTY_IO.cpuRounds(31))
        assertEquals(0, TierESharedOpenatOracleWorkload.Profile.BURSTY_IO.cpuRounds(32))
        assertEquals(1_500_000, TierESharedOpenatOracleWorkload.Profile.CALIBRATION_CPU.cpuRounds(0))
        assertEquals(0, TierESharedOpenatOracleWorkload.Profile.STACK_DIVERSE.cpuRounds(0))
        assertEquals(0, TierESharedOpenatOracleWorkload.Profile.STACK_DIVERSE.callDepth(0))
        assertEquals(15, TierESharedOpenatOracleWorkload.Profile.STACK_DIVERSE.callDepth(15))
        assertEquals(0, TierESharedOpenatOracleWorkload.Profile.STACK_DIVERSE.callDepth(16))
        assertEquals(true, TierESharedOpenatOracleWorkload.Profile.CLASS_CHURN.usesFreshProxy(0))
        assertEquals(false, TierESharedOpenatOracleWorkload.Profile.CLASS_CHURN.usesFreshProxy(63))
        assertEquals(true, TierESharedOpenatOracleWorkload.Profile.CLASS_CHURN.usesFreshProxy(64))
    }

    @Test
    fun `shared workload reports deterministic completed operations`() {
        val first = TierECommonFileWorkload.run(4)
        val second = TierECommonFileWorkload.run(4)

        assertEquals(4, first.completedOperations)
        assertEquals(first.checksum, second.checksum)
        assertTrue(first.workloadNanos > 0)
    }

    @Test
    fun `oracle manifest is atomically published as a complete fd set`() {
        val directory = Files.createTempDirectory("tier-e-oracle-manifest-")
        val manifest = directory.resolve("openat-fds")

        TierESharedOpenatOracleWorkload.publishManifest(manifest, Path.of("/tmp/oracle-root"), listOf(41, 42, 43))

        assertEquals(
            listOf("root=/tmp/oracle-root", "fd=41", "fd=42", "fd=43"),
            Files.readAllLines(manifest),
        )
    }

    @Test
    fun `batch execution preserves the workload contract across warmup and measured batches`() {
        val batches = TierECommonFileWorkload.runBatches(warmups = 2, batches = 3, iterations = 4)

        assertEquals(5, batches.size)
        assertEquals(listOf("WARMUP", "WARMUP", "MEASURED", "MEASURED", "MEASURED"), batches.map { it.phase.name })
        assertTrue(batches.all { it.result.completedOperations == 4 })
        assertEquals(1, batches.map { it.result.checksum }.distinct().size)
    }

    @Test
    fun `write oracle assigns a distinct syscall-visible operation id to every batch operation`() {
        val expected = TierEWriteOracleWorkload.expectedOperationIds(warmups = 2, batches = 3, iterations = 4)
        val batches = TierEWriteOracleWorkload.runBatches(warmups = 2, batches = 3, iterations = 4)

        assertEquals(20, expected.size)
        assertEquals(5, batches.size)
        assertEquals(20, batches.sumOf { it.result.completedOperations })
    }
}
