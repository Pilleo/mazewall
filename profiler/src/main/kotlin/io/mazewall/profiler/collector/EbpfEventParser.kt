package io.mazewall.profiler.collector

import io.mazewall.platform.lifecycle.Tid
import io.mazewall.profiler.api.NetworkEndpoint
import io.mazewall.profiler.api.ObservationCorrelation
import io.mazewall.profiler.api.ObservationSource
import io.mazewall.profiler.api.ProfileObservation

/**
 * Sidecar-neutral eBPF event text.
 *
 * One observation per line. Fields are `key=value` (whitespace-separated).
 * Required: `kind` (`uring` | `syscall` | `connect`) and identity (`tid`).
 *
 * Example:
 * `kind=uring tid=12 tgid=10 ktime=99 opcode=IORING_OP_OPENAT path=/tmp/x`
 */
internal class EbpfParseResult(
    observations: List<ProfileObservation>,
    public val droppedLines: Int,
) {
    public val observations: List<ProfileObservation> = java.util.List.copyOf(observations)
}

internal object EbpfEventParser {
    public fun parse(log: String): EbpfParseResult {
        val observations = mutableListOf<ProfileObservation>()
        var dropped = 0
        for (raw in log.lineSequence()) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val obs = parseLine(trimmed)
            if (obs == null) dropped++ else observations.add(obs)
        }
        return EbpfParseResult(observations, dropped)
    }

    public fun parseLine(line: String): ProfileObservation? {
        val fields =
            line
                .trim()
                .takeUnless { it.isEmpty() || it.startsWith("#") }
                ?.let(::parseFields) ?: return null
        val tid = fields["tid"]?.toIntOrNull() ?: return null
        val tgid = fields["tgid"]?.toIntOrNull() ?: tid
        val ktime = fields["ktime"]?.toLongOrNull()
        val corr = ObservationCorrelation(tgid, Tid(tid), ktime)
        val path = fields["path"]
        val paths = if (path.isNullOrEmpty()) emptyList() else listOf(path)
        return when (fields["kind"] ?: "uring") {
            "uring" -> fields["opcode"]?.let { ProfileObservation.IoUring(corr, ObservationSource.EBPF, it, paths) }
            "syscall" -> fields["name"]?.let { ProfileObservation.Syscall(corr, ObservationSource.EBPF, it.uppercase(), paths = paths) }
            "connect" -> fields["host"]?.let { host ->
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
    private fun parseFields(line: String): Map<String, String>? {
        val fields = linkedMapOf<String, String>()
        var i = 0
        while (i < line.length) {
            if (line[i].isWhitespace()) {
                i++
                continue
            }
            val keyStart = i
            while (i < line.length && line[i] != '=' && !line[i].isWhitespace()) i++
            if (i == keyStart || i == line.length || line[i] != '=') return null
            val key = line.substring(keyStart, i)
            val value = readValue(line, i + 1) ?: return null
            i = value.end
            fields[key] = value.text
        }
        return fields
    }

    private data class FieldValue(
        val text: String,
        val end: Int,
    )

    private fun readValue(
        line: String,
        start: Int,
    ): FieldValue? {
        if (line.getOrNull(start) != '"') {
            var end = start
            while (end < line.length && !line[end].isWhitespace()) end++
            return FieldValue(line.substring(start, end), end)
        }

        val value = StringBuilder()
        var i = start + 1
        while (i < line.length) {
            when (val char = line[i++]) {
                '"' -> return FieldValue(value.toString(), i).takeIf { i == line.length || line[i].isWhitespace() }
                '\\' -> if (i < line.length) value.append(line[i++])
                else -> value.append(char)
            }
        }
        return null
    }
}
