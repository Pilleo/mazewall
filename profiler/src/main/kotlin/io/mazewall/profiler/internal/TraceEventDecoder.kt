package io.mazewall.profiler.internal

import io.mazewall.platform.lifecycle.Tid
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.api.TraceEvent
import java.io.DataInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Pure bounded decoder for one profiler trace-event body after its TID header. */
internal object TraceEventDecoder {
    private const val MAX_SYSCALL_NAME_BYTES = 256
    private const val MAX_ARGUMENTS = 6
    private const val MAX_PATHS = 8
    private const val MAX_PATH_BYTES = 4096

    fun decodeBody(
        input: DataInputStream,
        tidValue: Int,
    ): TraceEventDecodeResult =
        try {
            val version = input.readInt()
            require(version == PathResolutionWire.VERSION) { "unsupported profiler event version: $version" }
            val syscallNameLength = input.readInt()
            val syscallName = readUtf8(input, syscallNameLength, MAX_SYSCALL_NAME_BYTES, "syscall name")
            val argumentCount = input.readInt()
            require(argumentCount in 0..MAX_ARGUMENTS) { "invalid argument count: $argumentCount" }
            val arguments = LongArray(argumentCount) { input.readLong() }
            val pathCount = input.readInt()
            require(pathCount in 0..MAX_PATHS) { "invalid path count: $pathCount" }
            val paths = List(pathCount) {
                val length = input.readInt()
                readUtf8(input, length, MAX_PATH_BYTES, "path")
            }
            val resolution = PathResolutionWire.read(input, paths)
            TraceEventDecodeResult.Event(
                event = TraceEvent.fromSyscallEvent(SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(tidValue), syscallName, arguments.toList(), resolution)),
                syscallNameLength = syscallNameLength,
                argumentCount = argumentCount,
            )
        } catch (failure: IOException) {
            TraceEventDecodeResult.ProtocolViolation("truncated trace event: ${failure.message}")
        } catch (failure: IllegalArgumentException) {
            TraceEventDecodeResult.ProtocolViolation(failure.message ?: "invalid trace event")
        }

    private fun readUtf8(
        input: DataInputStream,
        length: Int,
        limit: Int,
        field: String,
    ): String {
        require(length in 0..limit) { "$field length $length exceeds limit $limit" }
        return decodeTraceUtf8(ByteArray(length).also(input::readFully))
    }
}

/** Invalid UTF-8 is a malformed frame, not a different diagnostic or filesystem path. */
internal fun decodeTraceUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    .decode(java.nio.ByteBuffer.wrap(bytes))
    .toString()

internal sealed interface TraceEventDecodeResult {
    data class Event(
        val event: TraceEvent,
        val syscallNameLength: Int,
        val argumentCount: Int,
    ) : TraceEventDecodeResult

    data class ProtocolViolation(
        val reason: String,
    ) : TraceEventDecodeResult
}
