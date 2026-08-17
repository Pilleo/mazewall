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
        val fields = linkedMapOf<String, String>()
        for (token in trimmed.split(Regex("\\s+"))) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            fields[token.substring(0, eq)] = token.substring(eq + 1)
        }
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
}
