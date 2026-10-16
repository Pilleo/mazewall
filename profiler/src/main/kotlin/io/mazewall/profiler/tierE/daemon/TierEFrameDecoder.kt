package io.mazewall.profiler.tierE.daemon

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.SocketTimeoutException

internal sealed interface FrameReadResult {
    data object Idle : FrameReadResult

    data object PartialTimeout : FrameReadResult

    data object DeadlineExceeded : FrameReadResult

    data object EndOfStream : FrameReadResult

    data class Frame(
        val value: String,
    ) : FrameReadResult
}

/** Incrementally reads version-1 modified-UTF framing without losing byte position. */
internal class TierEFrameDecoder(
    private val input: InputStream,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    fun readFrame(frameDeadlineNanos: Long): FrameReadResult {
        val header = ByteArray(2)
        var headerBytes = 0
        while (headerBytes < header.size) {
            if (headerBytes > 0 && nowNanos() >= frameDeadlineNanos) return FrameReadResult.DeadlineExceeded
            when (val next = readByte()) {
                ByteRead.Timeout -> return if (headerBytes == 0) FrameReadResult.Idle else FrameReadResult.PartialTimeout
                ByteRead.End -> return if (headerBytes == 0) FrameReadResult.EndOfStream else FrameReadResult.PartialTimeout
                is ByteRead.Value -> header[headerBytes++] = next.value
            }
        }
        val payloadLength = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
        val payload = ByteArray(payloadLength)
        var payloadBytes = 0
        while (payloadBytes < payload.size) {
            if (nowNanos() >= frameDeadlineNanos) return FrameReadResult.DeadlineExceeded
            when (val next = readByte()) {
                ByteRead.Timeout,
                ByteRead.End,
                -> return FrameReadResult.PartialTimeout
                is ByteRead.Value -> payload[payloadBytes++] = next.value
            }
        }
        if (nowNanos() >= frameDeadlineNanos) return FrameReadResult.DeadlineExceeded
        val encoded = ByteArray(header.size + payload.size)
        header.copyInto(encoded)
        payload.copyInto(encoded, header.size)
        val value = DataInputStream(ByteArrayInputStream(encoded)).use { it.readUTF() }
        return FrameReadResult.Frame(value)
    }

    private sealed interface ByteRead {
        data class Value(
            val value: Byte,
        ) : ByteRead

        data object Timeout : ByteRead

        data object End : ByteRead
    }

    private fun readByte(): ByteRead =
        try {
        input.read().let { if (it < 0) ByteRead.End else ByteRead.Value(it.toByte()) }
    } catch (_: SocketTimeoutException) {
        ByteRead.Timeout
    }
}
