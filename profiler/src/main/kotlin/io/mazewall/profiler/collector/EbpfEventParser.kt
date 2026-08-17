package io.mazewall.profiler.collector

import io.mazewall.core.Tid
import io.mazewall.profiler.NetworkEndpoint
import io.mazewall.profiler.ObservationCorrelation
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileObservation

/**
 * Sidecar-neutral eBPF event text.
 *
 * One observation per line. Fields are `key=value` (whitespace-separated).
 * Required: `kind` (`uring` | `syscall` | `connect`) and identity (`tid`).
 *
 * Example:
 * `kind=uring tid=12 tgid=10 ktime=99 opcode=IORING_OP_OPENAT path=/tmp/x`
 */
public object EbpfEventParser {
    public fun parse(log: String): List<ProfileObservation> =
        log.lineSequence().mapNotNull { parseLine(it) }.toList()

    public fun parseLine(line: String): ProfileObservation? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val fields = parseFields(trimmed)
        val tid = fields["tid"]?.toIntOrNull() ?: return null
        val tgid = fields["tgid"]?.toIntOrNull() ?: tid
        val ktime = fields["ktime"]?.toLongOrNull()
        val corr = ObservationCorrelation(tgid, Tid(tid), ktime)
        val path = fields["path"]
        val paths = if (path.isNullOrEmpty()) emptyList() else listOf(path)
        return when (fields["kind"] ?: "uring") {
            "uring" -> {
                val opcode = fields["opcode"] ?: return null
                ProfileObservation.IoUring(corr, ObservationSource.EBPF, opcode, paths)
            }
            "syscall" -> {
                val name = fields["name"]?.uppercase() ?: return null
                ProfileObservation.Syscall(corr, ObservationSource.EBPF, name, paths = paths)
            }
            "connect" -> {
                val host = fields["host"] ?: return null
                val port = fields["port"]?.toIntOrNull()
                ProfileObservation.Connect(corr, ObservationSource.EBPF, NetworkEndpoint(host, port))
            }
            else -> null
        }
    }

    /**
     * `key=value` tokens. Values may be double-quoted and may contain spaces
     * (`path="/tmp/My File"`). `\"` and `\\` inside quotes are unescaped.
     */
    internal fun parseFields(line: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            val eq = line.indexOf('=', i)
            if (eq <= i) break
            val key = line.substring(i, eq)
            i = eq + 1
            if (i < line.length && line[i] == '"') {
                i++
                val value = StringBuilder()
                while (i < line.length && line[i] != '"') {
                    if (line[i] == '\\' && i + 1 < line.length) {
                        value.append(line[i + 1])
                        i += 2
                    } else {
                        value.append(line[i])
                        i++
                    }
                }
                if (i < line.length && line[i] == '"') i++
                fields[key] = value.toString()
            } else {
                val start = i
                while (i < line.length && !line[i].isWhitespace()) i++
                fields[key] = line.substring(start, i)
            }
        }
        return fields
    }
}
