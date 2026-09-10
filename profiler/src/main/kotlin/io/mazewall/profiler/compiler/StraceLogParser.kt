package io.mazewall.profiler.compiler

import io.mazewall.core.Tid
import io.mazewall.ffi.NativeConstants
import io.mazewall.profiler.NetworkEndpoint
import io.mazewall.profiler.ObservationCorrelation
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.OpenFlagsEvidence
import io.mazewall.profiler.ProfileObservation
import io.mazewall.profiler.engine.PathResolutionFailure
import io.mazewall.profiler.engine.combinePathResolution
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Converts completed `strace -f` records into immutable observations. */
public object StraceLogParser {
    private val fileSystemCharset: Charset = System
        .getProperty("sun.jnu.encoding")
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?: Charset.defaultCharset()
    private val inet = Regex("""sin_port=htons\((\d+)\).*sin_addr=inet_addr\("([^"]+)"\)""")
    private val inetRev = Regex("""sin_addr=inet_addr\("([^"]+)"\).*sin_port=htons\((\d+)\)""")
    private val inet6 = Regex("""sin6_port=htons\((\d+)\).*inet_pton\(AF_INET6,\s*"([^"]+)"""")
    private val inet6Rev = Regex("""inet_pton\(AF_INET6,\s*"([^"]+)".*sin6_port=htons\((\d+)\)""")

    private val pathOperands = mapOf(
        "OPEN" to intArrayOf(0),
        "CREAT" to intArrayOf(0),
        "UNLINK" to intArrayOf(0),
        "RMDIR" to intArrayOf(0),
        "MKDIR" to intArrayOf(0),
        "CHDIR" to intArrayOf(0),
        "CHROOT" to intArrayOf(0),
        "ACCESS" to intArrayOf(0),
        "STAT" to intArrayOf(0),
        "LSTAT" to intArrayOf(0),
        "CHMOD" to intArrayOf(0),
        "CHOWN" to intArrayOf(0),
        "LCHOWN" to intArrayOf(0),
        "TRUNCATE" to intArrayOf(0),
        "UTIME" to intArrayOf(0),
        "UTIMES" to intArrayOf(0),
        "READLINK" to intArrayOf(0),
        "EXECVE" to intArrayOf(0),
        "LINK" to intArrayOf(0, 1),
        "SYMLINK" to intArrayOf(0, 1),
        "RENAME" to intArrayOf(0, 1),
        "OPENAT" to intArrayOf(1),
        "OPENAT2" to intArrayOf(1),
        "EXECVEAT" to intArrayOf(1),
        "NEWFSTATAT" to intArrayOf(1),
        "FSTATAT" to intArrayOf(1),
        "UNLINKAT" to intArrayOf(1),
        "MKDIRAT" to intArrayOf(1),
        "FACCESSAT" to intArrayOf(1),
        "FACCESSAT2" to intArrayOf(1),
        "FCHMODAT" to intArrayOf(1),
        "FCHOWNAT" to intArrayOf(1),
        "READLINKAT" to intArrayOf(1),
        "UTIMENSAT" to intArrayOf(1),
        "SYMLINKAT" to intArrayOf(0, 2),
        "LINKAT" to intArrayOf(1, 3),
        "RENAMEAT" to intArrayOf(1, 3),
        "RENAMEAT2" to intArrayOf(1, 3),
    )

    private val openFlagValues = mapOf(
        "O_RDONLY" to 0L,
        "O_WRONLY" to 1L,
        "O_RDWR" to 2L,
        "O_ACCMODE" to 3L,
        "O_CREAT" to 0x40L,
        "O_EXCL" to 0x80L,
        "O_NOCTTY" to 0x100L,
        "O_TRUNC" to 0x200L,
        "O_APPEND" to 0x400L,
        "O_NONBLOCK" to 0x800L,
        "O_DSYNC" to 0x1000L,
        "O_ASYNC" to 0x2000L,
        "O_DIRECT" to 0x4000L,
        "O_LARGEFILE" to 0x8000L,
        "O_DIRECTORY" to 0x10000L,
        "O_NOFOLLOW" to 0x20000L,
        "O_CLOEXEC" to 0x80000L,
        "O_SYNC" to 0x101000L,
        "O_PATH" to NativeConstants.O_PATH.toLong(),
        "O_TMPFILE" to 0x410000L,
    )

    public class StraceParseResult(
        observations: List<ProfileObservation>,
        public val droppedRecords: Int,
    ) {
        public val observations: List<ProfileObservation> = java.util.List.copyOf(observations)
    }

    public fun parseWithStats(log: String): StraceParseResult {
        val observations = mutableListOf<ProfileObservation>()
        var dropped = 0
        for (line in log.lineSequence()) {
            val cleaned = line.trim()
            if (cleaned.isEmpty() || cleaned.startsWith("+++") || cleaned.startsWith("---")) continue
            if (cleaned.contains("<unfinished") || cleaned.contains("resumed>")) {
                dropped++
                continue
            }
            val observation = parseLine(cleaned)
            if (observation != null) {
                observations += observation
            } else if (cleaned.contains('(') || cleaned.contains(')')) {
                dropped++
            }
        }
        return StraceParseResult(java.util.List.copyOf(observations), dropped)
    }

    public fun parse(log: String): List<ProfileObservation> = parseWithStats(log).observations

    public fun parseLine(line: String): ProfileObservation? {
        val cleaned = line.trim()
        if (cleaned.isEmpty() || cleaned.startsWith("+++") || cleaned.startsWith("---")) return null
        if (cleaned.contains("<unfinished") || cleaned.contains("resumed>")) return null
        val openParen = cleaned.indexOf('(')
        if (openParen <= 0) return null
        val beforeParen = cleaned.substring(0, openParen).trim()
        val prefix = beforeParen.split(Regex("\\s+"))
        val syscallName = prefix.lastOrNull()?.uppercase().orEmpty()
        if (syscallName.isEmpty()) return null
        val pid = prefix.firstOrNull()?.toIntOrNull() ?: 0
        val arguments = completedArguments(cleaned, openParen) ?: return null
        val operands = tokenizeArguments(arguments)
        val correlation = ObservationCorrelation(tgid = pid, tid = Tid(pid))

        if (syscallName == "CONNECT") {
            parseConnect(arguments)?.let { return ProfileObservation.Connect(correlation, ObservationSource.STRACE, it) }
        }

        val pathFailures = mutableListOf<PathResolutionFailure>()
        val paths = buildList {
            (pathOperands[syscallName] ?: intArrayOf()).forEachIndexed { pathIndex, operandIndex ->
                when (val decoded = operands.getOrNull(operandIndex)?.decodeQuoted()) {
                    is DecodedPath.Complete -> add(decoded.value)

                    is DecodedPath.Incomplete -> {
                        pathFailures += PathResolutionFailure(
                            operandIndex = pathIndex,
                            reason = PathResolutionFailure.Reason.Truncated,
                            detail = decoded.detail,
                        )
                    }

                    null -> {
                        pathFailures += PathResolutionFailure(
                            operandIndex = pathIndex,
                            reason = PathResolutionFailure.Reason.Missing,
                            detail = "strace did not report a quoted pathname for $syscallName operand $pathIndex",
                        )
                    }
                }
            }
        }
        val flagsEvidence = when (syscallName) {
            "OPEN" -> operands.getOrNull(1)?.let(::parseOpenFlags)
            "OPENAT" -> operands.getOrNull(2)?.let(::parseOpenFlags)
            "OPENAT2" -> operands.getOrNull(2)?.let(::parseOpenHowFlags)
            else -> null
        }
        val observation = ProfileObservation.Syscall(
            correlation = correlation,
            source = ObservationSource.STRACE,
            name = syscallName,
            paths = paths,
            openFlags = (flagsEvidence as? OpenFlagsEvidence.Complete)?.value,
            openFlagsEvidence = flagsEvidence,
        )
        return observation.withPathResolution(combinePathResolution(paths, pathFailures))
    }

    private fun completedArguments(
        line: String,
        openParen: Int,
    ): String? {
        var quoted = false
        var escaped = false
        var depth = 1
        for (index in openParen + 1 until line.length) {
            val char = line[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '(' -> depth++
                    ')' -> if (--depth == 0) return line.substring(openParen + 1, index)
                }
            }
        }
        return null
    }

    internal fun tokenizeArguments(arguments: String): List<String> {
        val result = mutableListOf<String>()
        var quoted = false
        var escaped = false
        var depth = 0
        var start = 0
        arguments.forEachIndexed { index, char ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) {
                        result += arguments.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
        }
        result += arguments.substring(start).trim()
        return java.util.List.copyOf(result)
    }

    private fun parseOpenHowFlags(structure: String): OpenFlagsEvidence? {
        val body = structure.trim().removePrefix("{").removeSuffix("}")
        val flags = tokenizeArguments(body)
            .firstOrNull { it.substringBefore('=').trim() == "flags" }
            ?.substringAfter('=', "")
            ?.trim()
        return flags?.takeIf(String::isNotEmpty)?.let(::parseOpenFlags)
    }

    private fun parseOpenFlags(raw: String): OpenFlagsEvidence {
        var value = 0L
        val unknown = linkedSetOf<String>()
        raw.split('|').map(String::trim).filter(String::isNotEmpty).forEach { token ->
            val parsed = openFlagValues[token] ?: token.parseInteger()
            if (parsed == null) unknown += token else value = value or parsed
        }
        return if (unknown.isEmpty()) OpenFlagsEvidence.Complete(value) else OpenFlagsEvidence.Incomplete(value, unknown)
    }

    private fun String.parseInteger(): Long? =
        when {
            startsWith("0x", ignoreCase = true) -> drop(2).toLongOrNull(16)
            matches(Regex("[0-9]+")) -> toLongOrNull()
            else -> null
        }

    private fun String.decodeQuoted(): DecodedPath? {
        val text = trim()
        if (text.length < 2 || text.first() != '"') return null
        val bytes = ByteArrayOutputStream()
        val literal = StringBuilder()
        var index = 1
        while (index < text.length) {
            val char = text[index++]
            if (char == '"') {
                val suffix = text.substring(index).trim()
                if (suffix.isNotEmpty()) {
                    return DecodedPath.Incomplete(
                        if (suffix.startsWith("...")) "strace truncated the quoted pathname" else "unexpected text after quoted pathname: $suffix",
                    )
                }
                if (!flushLiteral(literal, bytes)) return DecodedPath.Incomplete("pathname is not encodable as ${fileSystemCharset.name()}")
                return try {
                    val decoded = fileSystemCharset
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    DecodedPath.Complete(decoded.toString())
                } catch (_: CharacterCodingException) {
                    DecodedPath.Incomplete("pathname contains undecodable ${fileSystemCharset.name()} bytes")
                }
            }
            if (char != '\\' || index >= text.length) {
                literal.append(char)
                continue
            }
            if (!flushLiteral(literal, bytes)) return DecodedPath.Incomplete("pathname is not encodable as ${fileSystemCharset.name()}")
            val escape = text[index++]
            when (escape) {
                '\\', '"' -> bytes.write(escape.code)
                'a' -> bytes.write(0x07)
                'b' -> bytes.write(0x08)
                't' -> bytes.write(0x09)
                'n' -> bytes.write(0x0a)
                'v' -> bytes.write(0x0b)
                'f' -> bytes.write(0x0c)
                'r' -> bytes.write(0x0d)
                'x' -> {
                    val digits = text.substring(index, (index + 2).coerceAtMost(text.length)).takeWhile { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    if (digits.isEmpty()) {
                        bytes.write('x'.code)
                    } else {
                        bytes.write(digits.toInt(16))
                        index += digits.length
                    }
                }
                in '0'..'7' -> {
                    val tail = text.substring(index, (index + 2).coerceAtMost(text.length)).takeWhile { it in '0'..'7' }
                    bytes.write((escape + tail).toInt(8))
                    index += tail.length
                }
                else -> bytes.write(escape.code)
            }
        }
        return DecodedPath.Incomplete("strace did not terminate the quoted pathname")
    }

    private fun flushLiteral(
        literal: StringBuilder,
        output: ByteArrayOutputStream,
    ): Boolean {
        if (literal.isEmpty()) return true
        return try {
            val encoded = fileSystemCharset
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(literal))
            output.write(encoded.toByteArray())
            literal.setLength(0)
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also(::get)

    private sealed interface DecodedPath {
        data class Complete(
            val value: String,
        ) : DecodedPath

        data class Incomplete(
            val detail: String,
        ) : DecodedPath
    }

    private fun parseConnect(args: String): NetworkEndpoint? {
        inet.find(args)?.let { return NetworkEndpoint(it.groupValues[2], it.groupValues[1].toInt()) }
        inetRev.find(args)?.let { return NetworkEndpoint(it.groupValues[1], it.groupValues[2].toInt()) }
        inet6.find(args)?.let { return NetworkEndpoint(it.groupValues[2], it.groupValues[1].toInt()) }
        inet6Rev.find(args)?.let { return NetworkEndpoint(it.groupValues[1], it.groupValues[2].toInt()) }
        return null
    }
}
