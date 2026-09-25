package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

public object TierETargetCpuSample {
    public data class Validation(
        val valid: Boolean,
        val reason: String,
        val cpuNs: Long? = null,
        val sample: JsonObject? = null,
    )

    public fun validate(
        directory: Path,
        expectedHostPid: Long? = null,
        expectedStarttime: Long? = null,
    ): Validation {
        val file = directory.resolve("target-cpu.json")
        if (!Files.isRegularFile(file)) return Validation(false, "missing_target_cpu_sample")
        val sample = try {
            Json.parseToJsonElement(Files.readString(file)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Validation(false, "target_cpu_sample_malformed")

        fun long(name: String): Long? = (sample[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

        fun text(name: String): String? = (sample[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (long("schema_version") != 1L ||
            long("version") != 1L ||
            !positive(long("pid")) ||
            !positive(long("host_pid")) ||
            !positive(long("starttime")) ||
            long("target_cpu_ns")?.let { it >= 0 } != true
        ) {
            return Validation(false, "target_cpu_sample_invalid", sample = sample)
        }
        if (text("sample_boundary") != "after_workload_before_exit") {
            return Validation(false, "target_cpu_sample_boundary_mismatch", sample = sample)
        }
        if (text("unit") != "ns") return Validation(false, "target_cpu_sample_unit_mismatch", sample = sample)
        if (expectedHostPid != null && long("host_pid") != expectedHostPid) {
            return Validation(false, "target_cpu_sample_host_pid_mismatch", sample = sample)
        }
        if (expectedStarttime != null && long("starttime") != expectedStarttime) {
            return Validation(false, "target_cpu_sample_starttime_mismatch", sample = sample)
        }
        return Validation(true, "target_cpu_sample_ok", long("target_cpu_ns"), sample)
    }
}

public object TierECollectorCpuSample {
    public data class Validation(
        val valid: Boolean,
        val reason: String,
        val cpuTicks: Long? = null,
        val cpuNs: Long? = null,
        val sample: JsonObject? = null,
    )

    public fun sample(
        pid: Long,
        hz: Long,
        procRoot: Path = Path.of("/proc"),
        output: Path,
        reader: (Path, Int) -> String = { path, _ -> Files.readString(path) },
    ): Validation {
        if (!positive(pid)) return Validation(false, "collector_cpu_sample_pid_invalid")
        if (!positive(hz)) return Validation(false, "collector_cpu_sample_hz_invalid")
        val directory = procRoot.resolve(pid.toString())
        var statReads = 0

        fun read(path: Path): String {
            if (path.fileName.toString() == "stat") statReads++
            return reader(path, statReads)
        }

        fun stat(raw: String): Triple<Long, Long, Long>? {
            val close = raw.lastIndexOf(')')
            if (close < 0 || close + 2 > raw.length) return null
            val fields = raw.substring(close + 2).trim().split(Regex("\\s+"))
            if (fields.size < 20) return null
            val utime = fields[11].toLongOrNull() ?: return null
            val stime = fields[12].toLongOrNull() ?: return null
            val starttime = fields[19].toLongOrNull() ?: return null
            if (utime < 0 || stime < 0 || starttime <= 0) return null
            return Triple(utime, stime, starttime)
        }
        val first = try {
            stat(read(directory.resolve("stat")))
        } catch (_: NoSuchFileException) {
            return Validation(false, "collector_cpu_sample_source_missing")
        } catch (_: Exception) {
            return Validation(false, "collector_cpu_sample_source_unreadable")
        } ?: return Validation(false, "collector_cpu_sample_source_unreadable")
        val status = try {
            read(directory.resolve("status"))
        } catch (_: NoSuchFileException) {
            return Validation(false, "collector_cpu_sample_source_missing")
        } catch (_: Exception) {
            return Validation(false, "collector_cpu_sample_source_unreadable")
        }
        val second = try {
            stat(read(directory.resolve("stat")))
        } catch (_: NoSuchFileException) {
            return Validation(false, "collector_cpu_sample_source_missing")
        } catch (_: Exception) {
            return Validation(false, "collector_cpu_sample_source_unreadable")
        } ?: return Validation(false, "collector_cpu_sample_source_unreadable")
        if (first.third != second.third) return Validation(false, "collector_cpu_sample_process_unstable")
        val peak = Regex("(?m)^VmHWM:\\s+(\\d+)\\s+kB")
            .find(status)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: return Validation(false, "collector_cpu_sample_invalid_counters")
        val ticks = try {
            Math.addExact(second.first, second.second)
        } catch (_: ArithmeticException) {
            return Validation(false, "collector_cpu_sample_invalid_counters")
        }
        val cpu = (ticks.toDouble() * 1_000_000_000.0 / hz).let { if (it.isFinite() && it >= 0) Math.round(it) else null }
            ?: return Validation(false, "collector_cpu_sample_invalid_counters")
        val value = buildJsonObject {
            put("schema_version", 1)
            put("collector_host_pid", pid)
            put("cpu_ticks", ticks)
            put("cpu_ns", cpu)
            put("peak_rss_kb", peak)
            put("unit", "ns")
            put("cpu_time_basis", "process_ticks_since_start")
            put("sample_boundary", "after_terminal_report_before_shutdown")
            put("collector_starttime", first.third)
        }
        val temp = output.resolveSibling("${output.fileName}.tmp.${ProcessHandle.current().pid()}")
        try {
            Files.writeString(temp, value.toString() + "\n")
            Files.move(temp, output, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.deleteIfExists(temp)
            return Validation(false, "collector_cpu_sample_write_failed")
        }
        return Validation(true, "collector_cpu_sample_ok", ticks, cpu, value)
    }
}

private fun positive(value: Long?): Boolean = value != null && value in 1..9_007_199_254_740_991L
