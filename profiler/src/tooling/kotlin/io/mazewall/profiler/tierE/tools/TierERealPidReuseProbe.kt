package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Bounded diagnostic probe for the registered host PID; never changes PID controls. */
public object TierERealPidReuseProbe {
    public fun validReplacement(
        report: JsonObject,
        pid: Long,
        start: Long,
        replacementStart: Long,
    ): Boolean {
        fun JsonObject?.number(field: String): Long? = (this?.get(field) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val original = report["original"] as? JsonObject
        val replacement = report["replacement"] as? JsonObject
        return pid > 0 &&
            start > 0 &&
            replacementStart > 0 &&
            replacementStart != start &&
            original.number("pid") == pid &&
            original.number("starttime") == start &&
            replacement.number("pid") == pid &&
            replacement.number("starttime") == replacementStart &&
            report["host_pid_reuse_verdict"] == JsonPrimitive("OBSERVED") &&
            report["policy_eligibility"] == JsonPrimitive(false)
    }

    public fun starttime(stat: String): Long {
        val end = stat.lastIndexOf(')')
        require(end >= 0) { "Malformed /proc stat process name" }
        val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
        require(fields.size >= 20) { "Truncated /proc stat" }
        return fields[19].toLong()
    }

    public fun run(
        registeredPid: Long,
        registeredStarttime: Long,
        attempts: Int,
        deadline: Duration,
        hold: Duration,
        output: Path,
        proc: Path = Path.of("/proc"),
    ): JsonObject {
        require(registeredPid > 0 && registeredStarttime > 0 && attempts > 0 && !deadline.isZero && !deadline.isNegative)
        require(!hold.isNegative && hold < Duration.ofSeconds(60))
        val limit = System.nanoTime() + deadline.toNanos()
        var attempted = 0
        var replacement: Pair<Long, Long>? = null
        var held: Process? = null
        try {
            while (attempted < attempts && System.nanoTime() < limit) {
                val candidate = ProcessBuilder("/bin/sleep", "60").start()
                try {
                    val observedStart = starttime(Files.readString(proc.resolve("${candidate.pid()}/stat")))
                    attempted++
                    if (candidate.pid() == registeredPid && observedStart != registeredStarttime) {
                        replacement = candidate.pid() to observedStart
                        held = candidate
                        break
                    }
                } finally {
                    if (held !== candidate) {
                        candidate.destroy()
                        check(candidate.waitFor(2, TimeUnit.SECONDS)) { "PID reuse candidate did not exit: ${candidate.pid()}" }
                    }
                }
            }
            val result = buildJsonObject {
                put("schema_version", 1)
                put("kernel", System.getProperty("os.version"))
                put("pid_max", Files.readString(proc.resolve("sys/kernel/pid_max")).trim().toInt())
                put("ns_last_pid", Files.readString(proc.resolve("sys/kernel/ns_last_pid")).trim().toInt())
                put(
                    "original",
                    buildJsonObject {
                    put("pid", registeredPid)
                    put("starttime", registeredStarttime)
                },
                )
                put("attempts", attempted)
                put(
                    "replacement",
                    replacement?.let { (pid, observedStart) ->
                    buildJsonObject {
                        put("pid", pid)
                        put("starttime", observedStart)
                    }
                } ?: JsonNull,
                )
                put("host_pid_reuse_verdict", if (replacement == null) "UNVERIFIED" else "OBSERVED")
                put("policy_eligibility", false)
                put("probe_contract", "real guest host PIDs and /proc starttime; registered target already exited")
            }
            Files.createDirectories(output.parent)
            Files.writeString(output, JsonObject(result).toString() + "\n")
            if (replacement != null && !hold.isZero) Thread.sleep(hold.toMillis())
            return result
        } finally {
            held?.let {
                it.destroy()
                if (!it.waitFor(2, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                    check(it.waitFor(2, TimeUnit.SECONDS)) { "Held PID reuse replacement did not exit: ${it.pid()}" }
                }
            }
        }
    }
}
