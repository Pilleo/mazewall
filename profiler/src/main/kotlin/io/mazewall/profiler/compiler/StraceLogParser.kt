package io.mazewall.profiler.compiler

import io.mazewall.core.Tid
import io.mazewall.profiler.NetworkEndpoint
import io.mazewall.profiler.ObservationCorrelation
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileObservation

/**
 * Translates `strace -f -e trace=file,network` lines into [ProfileObservation]s.
 * [io.mazewall.profiler.strace.StraceProfiler] must not build a BillOfBehavior itself.
 */
public object StraceLogParser {
    private val inet = Regex("""sin_port=htons\((\d+)\).*sin_addr=inet_addr\("([^"]+)"\)""")
    private val inetRev = Regex("""sin_addr=inet_addr\("([^"]+)"\).*sin_port=htons\((\d+)\)""")

    public fun parse(log: String): List<ProfileObservation> =
        log.lineSequence().mapNotNull { parseLine(it) }.toList()

    public fun parseLine(line: String): ProfileObservation? {
        val cleaned = line.trim()
        if (cleaned.isEmpty() || cleaned.startsWith("+++") || cleaned.startsWith("---")) return null

        val beforeParen = cleaned.substringBefore("(", "")
        if (beforeParen.isEmpty()) return null

        val tokens = beforeParen.split(Regex("\\s+"))
        val syscallName = tokens.last().uppercase()
        if (syscallName.isEmpty()) return null
        val pid = tokens.firstOrNull()?.toIntOrNull() ?: 0
        val corr = ObservationCorrelation(tgid = pid, tid = Tid(pid))
        val args = cleaned.substringAfter("(", "")

        parseConnect(args)?.let { endpoint ->
            return ProfileObservation.Connect(corr, ObservationSource.STRACE, endpoint)
        }

        val path = extractQuotedPath(args)
        val paths = if (path != null) listOf(path) else emptyList()
        val flags = if (isOpen(syscallName)) straceOpenFlags(args) else null
        return ProfileObservation.Syscall(
            correlation = corr,
            source = ObservationSource.STRACE,
            name = syscallName,
            paths = paths,
            openFlags = flags,
        )
    }

    private fun isOpen(name: String): Boolean = name == "OPEN" || name == "OPENAT" || name == "OPENAT2"

    private fun straceOpenFlags(args: String): Long {
        var flags = 0L
        if (args.contains("O_WRONLY")) flags = flags or 1L
        if (args.contains("O_RDWR")) flags = flags or 2L
        if (args.contains("O_CREAT")) flags = flags or 64L
        if (args.contains("O_TRUNC")) flags = flags or 512L
        return flags
    }

    private fun parseConnect(args: String): NetworkEndpoint? {
        inet.find(args)?.let { return NetworkEndpoint(it.groupValues[2], it.groupValues[1].toInt()) }
        inetRev.find(args)?.let { return NetworkEndpoint(it.groupValues[1], it.groupValues[2].toInt()) }
        return null
    }

    private fun extractQuotedPath(args: String): String? {
        val match = "\"(.*?)\"".toRegex().find(args)
        return match?.groupValues?.get(1)
    }
}
