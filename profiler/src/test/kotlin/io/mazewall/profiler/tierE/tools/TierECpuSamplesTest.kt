package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierECpuSamplesTest {
    @Test
    fun `target sample is bound to expected host pid and process incarnation`() {
        val dir = Files.createTempDirectory("tier-e-cpu-target-")
        try {
            Files.writeString(
                dir.resolve("target-cpu.json"),
                """{"schema_version":1,"version":1,"pid":1,"host_pid":3514,"starttime":133817,"sample_boundary":"after_workload_before_exit","unit":"ns","target_cpu_ns":5020000000}""",
            )
            val valid = TierETargetCpuSample.validate(dir, 3514, 133817)
            assertTrue(valid.valid)
            assertEquals(5_020_000_000L, valid.cpuNs)
            assertEquals("target_cpu_sample_starttime_mismatch", TierETargetCpuSample.validate(dir, 3514, 134936).reason)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `collector uses both CPU counters from one stable second proc snapshot`() {
        val dir = Files.createTempDirectory("tier-e-cpu-collector-")
        try {
            val proc = dir.resolve("proc/3514")
            Files.createDirectories(proc)
            val first = "3514 (tier-e-daemon) S 1 1000 1000 0 -1 4194560 100 0 0 0 40 12 0 0 20 0 8 0 987654 123456789 1000"
            val second = first.replace(" 40 12 ", " 70 19 ")
            val result = TierECollectorCpuSample.sample(3514, 100, dir.resolve("proc"), dir.resolve("sample.json")) { path, reads ->
                if (path.fileName.toString() == "stat") {
                    if (reads == 1) {
                        first
                    } else {
                        second
                    }
                } else {
                    "Name:\ttier-e-daemon\nVmHWM:\t245760 kB\n"
                }
            }
            assertTrue(result.valid, result.reason)
            assertEquals(89L, result.cpuTicks)
            assertEquals(890_000_000L, result.cpuNs)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `collector rejects recycled pid and does not publish sample`() {
        val dir = Files.createTempDirectory("tier-e-cpu-recycled-")
        try {
            val stat = "3514 (tier-e-daemon) S 1 1000 1000 0 -1 4194560 100 0 0 0 40 12 0 0 20 0 8 0 987654 123456789 1000"
            val output = dir.resolve("sample.json")
            val result = TierECollectorCpuSample.sample(3514, 100, dir.resolve("proc"), output) { path, reads ->
                if (path.fileName.toString() == "stat") {
                    if (reads == 1) {
                        stat
                    } else {
                        stat.replace("987654", "987655")
                    }
                } else {
                    "Name:\ttier-e-daemon\nVmHWM:\t245760 kB\n"
                }
            }
            assertFalse(result.valid)
            assertEquals("collector_cpu_sample_process_unstable", result.reason)
            assertFalse(Files.exists(output))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
