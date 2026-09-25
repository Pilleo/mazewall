package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    @ParameterizedTest
    @ValueSource(strings = ["missing-version", "bad-schema", "zero-pid", "zero-starttime", "negative-cpu", "wrong-boundary", "wrong-unit", "malformed-json"])
    fun `invalid target CPU samples reject with a reason`(scenario: String) {
        val dir = Files.createTempDirectory("tier-e-bad-target-")
        try {
            val original = """{"schema_version":1,"version":1,"pid":1,"host_pid":3514,"starttime":133817,"sample_boundary":"after_workload_before_exit","unit":"ns","target_cpu_ns":5020000000}"""
            val payload = when (scenario) {
                "missing-version" -> original.replace("\"version\":1,", "")
                "bad-schema" -> original.replace("\"schema_version\":1", "\"schema_version\":2")
                "zero-pid" -> original.replace("\"pid\":1", "\"pid\":0")
                "zero-starttime" -> original.replace("\"starttime\":133817", "\"starttime\":0")
                "negative-cpu" -> original.replace("\"target_cpu_ns\":5020000000", "\"target_cpu_ns\":-1")
                "wrong-boundary" -> original.replace("after_workload_before_exit", "after_terminal_report_before_shutdown")
                "wrong-unit" -> original.replace("\"unit\":\"ns\"", "\"unit\":\"ms\"")
                "malformed-json" -> "{broken"
                else -> error(scenario)
            }
            Files.writeString(dir.resolve("target-cpu.json"), payload)
            val result = TierETargetCpuSample.validate(dir, 3514, 133817)
            assertFalse(result.valid, scenario)
            assertTrue(result.reason.startsWith("target_cpu_sample_"), result.reason)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-stat", "negative-counters", "missing-rss", "zero-hz", "zero-pid"])
    fun `invalid collector samples never publish output`(scenario: String) {
        val dir = Files.createTempDirectory("tier-e-bad-collector-")
        try {
            val stat = "3514 (tier-e-daemon) S 1 1000 1000 0 -1 4194560 100 0 0 0 40 12 0 0 20 0 8 0 987654 123456789 1000"
            val output = dir.resolve("sample.json")
            val result = TierECollectorCpuSample.sample(
                if (scenario == "zero-pid") 0 else 3514,
                if (scenario == "zero-hz") 0 else 100,
                dir.resolve("proc"),
                output,
            ) { path, _ ->
                if (path.fileName.toString() == "stat") {
                    if (scenario == "missing-stat") throw java.nio.file.NoSuchFileException(path.toString())
                    if (scenario == "negative-counters") stat.replace(" 40 12 ", " -40 12 ") else stat
                } else if (scenario == "missing-rss") {
                    "Name:\ttier-e-daemon\n"
                } else {
                    "VmHWM:\t245760 kB\n"
                }
            }
            assertFalse(result.valid, scenario)
            assertTrue(result.reason.startsWith("collector_cpu_sample_"), result.reason)
            assertFalse(Files.exists(output))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-second-stat", "unreadable-stat", "negative-rss"])
    fun `collector source failure stays explicit and never publishes`(scenario: String) {
        val dir = Files.createTempDirectory("tier-e-collector-source-")
        try {
            val stat = "3514 (tier-e-daemon) S 1 1000 1000 0 -1 4194560 100 0 0 0 40 12 0 0 20 0 8 0 987654 123456789 1000"
            val output = dir.resolve("sample.json")
            val result = TierECollectorCpuSample.sample(3514, 100, dir.resolve("proc"), output) { path, reads ->
                when {
                    path.fileName.toString() == "stat" && scenario == "missing-second-stat" && reads == 2 ->
                        throw java.nio.file.NoSuchFileException(path.toString())
                    path.fileName.toString() == "stat" && scenario == "unreadable-stat" ->
                        throw java.io.IOException("unreadable stat")
                    path.fileName.toString() == "stat" -> stat
                    scenario == "negative-rss" -> "Name:\ttier-e-daemon\nVmHWM:\t-1 kB\n"
                    else -> "VmHWM:\t245760 kB\n"
                }
            }
            val expected = when (scenario) {
                "missing-second-stat" -> "collector_cpu_sample_source_missing"
                "unreadable-stat" -> "collector_cpu_sample_source_unreadable"
                else -> "collector_cpu_sample_invalid_counters"
            }
            assertEquals(expected, result.reason)
            assertFalse(Files.exists(output))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `collector rejects changed sample boundary before reading proc`() {
        val dir = Files.createTempDirectory("tier-e-collector-boundary-")
        try {
            val result = TierECollectorCpuSample.sample(
                3514,
                100,
                dir.resolve("proc"),
                dir.resolve("sample.json"),
                boundary = "after_workload_before_exit",
            )
            assertEquals("collector_cpu_sample_boundary_mismatch", result.reason)
            assertFalse(Files.exists(dir.resolve("sample.json")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
