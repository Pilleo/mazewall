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
    private val inet6 = Regex("""sin6_port=htons\((\d+)\).*inet_pton\(AF_INET6,\s*"([^"]+)"""")
    private val inet6Rev = Regex("""inet_pton\(AF_INET6,\s*"([^"]+)".*sin6_port=htons\((\d+)\)""")

    // Syscall name sets for path extraction (uppercase as parseLine produces)
    private val firstOnlySyscalls = setOf(
        "OPEN", "CREAT", "UNLINK", "RMDIR", "MKDIR", "CHDIR", "CHROOT",
        "ACCESS", "STAT", "LSTAT", "NEWFSTATAT", "FSTATAT",
        "CHMOD", "CHOWN", "LCHOWN", "TRUNCATE",
        "UTIME", "UTIMES", "FCHMOD", "FCHOWN", "FSTAT",
        "READLINK", "EXECVE", "EXECVEAT",
        "OPENAT", "OPENAT2",
    )

    private val twoPathSyscalls = setOf(
        "LINK", "SYMLINK", "RENAME",
    )

    private val firstPathAtSyscalls = setOf(
        "UNLINKAT", "MKDIRAT", "FACCESSAT", "FACCESSAT2",
        "FCHMODAT", "FCHOWNAT", "READLINKAT", "UTIMENSAT",
    )

    private val secondAndThirdAtSyscalls = setOf(
        "SYMLINKAT", "LINKAT", "RENAMEAT", "RENAMEAT2"
    )

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

        if (syscallName == "CONNECT") {
            parseConnect(args)?.let { endpoint ->
                return ProfileObservation.Connect(corr, ObservationSource.STRACE, endpoint)
            }
        }

        val paths = if (isPathBearing(syscallName)) extractQuotedPaths(syscallName, args) else emptyList()
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

    private fun isPathBearing(name: String): Boolean =
        name in firstOnlySyscalls ||
        name in twoPathSyscalls ||
        name in firstPathAtSyscalls ||
        name in secondAndThirdAtSyscalls

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
        inet6.find(args)?.let { return NetworkEndpoint(it.groupValues[2], it.groupValues[1].toInt()) }
        inet6Rev.find(args)?.let { return NetworkEndpoint(it.groupValues[1], it.groupValues[2].toInt()) }
        return null
    }

    /**
     * Extracts only the syscall pathname operands from strace args.
     * Not all quoted strings are paths - e.g., execve argv entries or readlink buffers.
     * Syscall names are uppercase (from parseLine).
     */
    private fun extractQuotedPaths(syscallName: String, args: String): List<String> {
        val allPaths = mutableListOf<String>()
        val matches = "\"(.*?)\"" .toRegex().findAll(args).map { it.groupValues[1] }.toList()

        when (syscallName) {
            // First-only: only the first quoted string is a path
            in firstOnlySyscalls -> {
                if (matches.isNotEmpty()) allPaths.add(matches[0])
            }

            // Two-path: first and second quoted strings are paths
            in twoPathSyscalls -> {
                if (matches.size >= 2) {
                    allPaths.add(matches[0])
                    allPaths.add(matches[1])
                }
            }

            // *at syscalls where first quoted string is the path (dirfd is numeric, not quoted)
            in firstPathAtSyscalls -> {
                if (matches.isNotEmpty()) allPaths.add(matches[0])
            }

            // *at syscalls: matches[0] and matches[1] are the two paths
            in secondAndThirdAtSyscalls -> {
                if (matches.size >= 2) {
                    allPaths.add(matches[0])
                    allPaths.add(matches[1])
                }
            }

            else -> {
                // Unknown path-bearing syscall: fail closed - no paths
                // This should not happen as isPathBearing checks the known sets
            }
        }

        return allPaths
    }
}
