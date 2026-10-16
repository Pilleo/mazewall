package io.mazewall.profiler.compiler

import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.profiler.api.NetworkEndpoint
import io.mazewall.profiler.api.ObservationCorrelation
import io.mazewall.profiler.api.ObservationSource
import io.mazewall.profiler.api.OpenFlagsEvidence
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.ProfileObservation
import io.mazewall.profiler.api.combinePathResolution
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Converts completed `strace -f` records into immutable observations. */
internal object StraceLogParser {
    private const val HEX_RADIX = 16
    private const val OCTAL_RADIX = 8
    private const val ASCII_BELL = 0x07
    private const val ASCII_BACKSPACE = 0x08
    private const val ASCII_TAB = 0x09
    private const val ASCII_NEWLINE = 0x0a
    private const val ASCII_VERTICAL_TAB = 0x0b
    private const val ASCII_FORM_FEED = 0x0c
    private const val ASCII_CARRIAGE_RETURN = 0x0d
    private val escapedByteValues = mapOf(
        '\\' to '\\'.code,
        '"' to '"'.code,
        'a' to ASCII_BELL,
        'b' to ASCII_BACKSPACE,
        't' to ASCII_TAB,
        'n' to ASCII_NEWLINE,
        'v' to ASCII_VERTICAL_TAB,
        'f' to ASCII_FORM_FEED,
        'r' to ASCII_CARRIAGE_RETURN,
    )

    private val fileSystemCharset: Charset
        get() = System
            .getProperty("sun.jnu.encoding")
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
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
        val call = parseCall(line) ?: return null
        if (call.name == "CONNECT") {
            parseConnect(call.arguments)?.let { return ProfileObservation.Connect(call.correlation, ObservationSource.STRACE, it) }
        }
        val pathEvidence = parsePaths(call.name, call.operands)
        val flagsEvidence = parseFlags(call.name, call.operands)
        val observation = ProfileObservation.Syscall(
            correlation = call.correlation,
            source = ObservationSource.STRACE,
            name = call.name,
            paths = pathEvidence.paths,
            openFlags = (flagsEvidence as? OpenFlagsEvidence.Complete)?.value,
            openFlagsEvidence = flagsEvidence,
        )
        return observation.withPathResolution(combinePathResolution(pathEvidence.paths, pathEvidence.failures))
    }

    // Guards reject incomplete open-ended strace records before operands can become evidence.
    private fun parseCall(line: String): ParsedCall? {
        val cleaned = line.trim().takeUnless {
            it.isEmpty() || it.startsWith("+++") || it.startsWith("---") || it.contains("<unfinished") || it.contains("resumed>")
        } ?: return null
        val header = parseCallHeader(cleaned) ?: return null
        val arguments = completedArguments(cleaned, header.openParen)
        return arguments?.let {
            ParsedCall(header.name, it, tokenizeArguments(it), ObservationCorrelation(tgid = header.pid, tid = Tid(header.pid)))
        }
    }

    private fun parseCallHeader(cleaned: String): ParsedCallHeader? {
        val openParen = cleaned.indexOf('(')
        val beforeParen = cleaned.takeIf { openParen > 0 }?.substring(0, openParen)?.trim() ?: return null
        val prefix = beforeParen.split(Regex("\\s+"))
        val name = prefix.lastOrNull()?.uppercase()?.takeIf(String::isNotEmpty) ?: return null
        return ParsedCallHeader(name, prefix.firstOrNull()?.toIntOrNull() ?: 0, openParen)
    }

    private fun parsePaths(
        syscallName: String,
        operands: List<String>,
    ): ParsedPaths {
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
        return ParsedPaths(paths, pathFailures)
    }

    private fun parseFlags(
        syscallName: String,
        operands: List<String>,
    ): OpenFlagsEvidence? =
        when (syscallName) {
            "OPEN" -> operands.getOrNull(1)?.let(::parseOpenFlags)
            "OPENAT" -> operands.getOrNull(2)?.let(::parseOpenFlags)
            "OPENAT2" -> operands.getOrNull(2)?.let(::parseOpenHowFlags) ?: missingOpenHowFlags()
            else -> null
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
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
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
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
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

    private fun parseOpenHowFlags(structure: String): OpenFlagsEvidence {
        val body = structure.trim().removePrefix("{").removeSuffix("}")
        val flags = tokenizeArguments(body)
            .firstOrNull { it.substringBefore('=').trim() == "flags" }
            ?.substringAfter('=', "")
            ?.trim()
        return flags?.takeIf(String::isNotEmpty)?.let(::parseOpenFlags) ?: missingOpenHowFlags()
    }

    private fun missingOpenHowFlags(): OpenFlagsEvidence = OpenFlagsEvidence.Incomplete(0, setOf("open_how.flags"))

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
            startsWith("0x", ignoreCase = true) -> drop(2).toLongOrNull(HEX_RADIX)
            matches(Regex("[0-9]+")) -> toLongOrNull()
            else -> null
        }

    private fun String.decodeQuoted(): DecodedPath? {
        val text = trim()
        if (text.length < 2 || text.first() != '"') return null
        val bytes = ByteArrayOutputStream()
        val literal = StringBuilder()
        var index = 1
        var outcome: DecodedPath? = null
        while (index < text.length && outcome == null) {
            val char = text[index++]
            if (char == '"') {
                outcome = finishQuoted(text, index, literal, bytes)
            } else if (char != '\\' || index >= text.length) {
                literal.append(char)
            } else if (!flushLiteral(literal, bytes)) {
                outcome = DecodedPath.Incomplete("pathname is not encodable as ${fileSystemCharset.name()}")
            } else {
                index = decodeEscape(text, index, bytes)
            }
        }
        return outcome ?: DecodedPath.Incomplete("strace did not terminate the quoted pathname")
    }

    private fun finishQuoted(
        text: String,
        index: Int,
        literal: StringBuilder,
        bytes: ByteArrayOutputStream,
    ): DecodedPath {
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

    private fun decodeEscape(
        text: String,
        escapeIndex: Int,
        bytes: ByteArrayOutputStream,
    ): Int {
        val escape = text[escapeIndex]
        escapedByteValues[escape]?.let {
            bytes.write(it)
            return escapeIndex + 1
        }
        var next = escapeIndex + 1
        when {
            escape == 'x' -> {
                val digits = text.substring(next, (next + 2).coerceAtMost(text.length)).takeWhile { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                bytes.write(if (digits.isEmpty()) 'x'.code else digits.toInt(HEX_RADIX))
                next += digits.length
            }
            escape in '0'..'7' -> {
                val tail = text.substring(next, (next + 2).coerceAtMost(text.length)).takeWhile { it in '0'..'7' }
                bytes.write((escape + tail).toInt(OCTAL_RADIX))
                next += tail.length
            }
            else -> bytes.write(escape.code)
        }
        return next
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

    private data class ParsedCall(
        val name: String,
        val arguments: String,
        val operands: List<String>,
        val correlation: ObservationCorrelation,
    )

    private data class ParsedCallHeader(
        val name: String,
        val pid: Int,
        val openParen: Int,
    )

    private data class ParsedPaths(
        val paths: List<String>,
        val failures: List<PathResolutionFailure>,
    )

    private fun parseConnect(args: String): NetworkEndpoint? {
        inet.find(args)?.let { return NetworkEndpoint(it.groupValues[2], it.groupValues[1].toInt()) }
        inetRev.find(args)?.let { return NetworkEndpoint(it.groupValues[1], it.groupValues[2].toInt()) }
        inet6.find(args)?.let { return NetworkEndpoint(it.groupValues[2], it.groupValues[1].toInt()) }
        inet6Rev.find(args)?.let { return NetworkEndpoint(it.groupValues[1], it.groupValues[2].toInt()) }
        return null
    }
}
