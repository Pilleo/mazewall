package io.mazewall.profiler.attribution

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Publication metadata carried outside the unchanged MZSD v2 record stream. */
public data class SnapshotPublication(
    public val publicationId: Long,
    public val watermark: Long,
    public val closed: Boolean,
) {
    init {
        require(publicationId >= 0) { "publication id must not be negative" }
        require(watermark >= 0) { "snapshot watermark must not be negative" }
    }
}

/** Versioned outer envelope for live MZSD snapshots. */
public object SnapshotEnvelope {
    private val MAGIC = "MZSE".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION: Short = 1
    private const val CLOSED_FLAG: Short = 1
    private const val HEADER_SIZE = 32

    public class Decoded(
        public val publication: SnapshotPublication,
        payload: ByteArray,
    ) {
        public val payload: ByteArray = payload.copyOf()
    }

    public fun encode(
        payload: ByteArray,
        publicationId: Long,
        watermark: Long,
        closed: Boolean,
    ): ByteArray {
        require(publicationId >= 0) { "publication id must not be negative" }
        require(watermark >= 0) { "snapshot watermark must not be negative" }
        val output = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        output.put(MAGIC)
        output.putShort(VERSION)
        output.putShort(if (closed) CLOSED_FLAG else 0)
        output.putLong(publicationId)
        output.putLong(watermark)
        output.putLong(payload.size.toLong())
        output.put(payload)
        return output.array()
    }

    public fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= HEADER_SIZE) { "snapshot envelope is shorter than its header" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(input::get)
        require(magic.contentEquals(MAGIC)) { "invalid snapshot envelope magic" }
        require(input.short == VERSION) { "unsupported snapshot envelope version" }
        val flags = input.short.toInt() and 0xffff
        require(flags and CLOSED_FLAG.toInt() == flags) { "unknown snapshot envelope flags" }
        val publicationId = input.long
        val watermark = input.long
        val payloadLength = input.long
        require(publicationId >= 0) { "snapshot publication id is negative" }
        require(watermark >= 0) { "snapshot watermark is negative" }
        require(payloadLength >= 0 && payloadLength <= input.remaining().toLong()) {
            "invalid snapshot payload length $payloadLength"
        }
        val payload = ByteArray(payloadLength.toInt()).also(input::get)
        require(!input.hasRemaining()) { "snapshot envelope has trailing bytes" }
        return Decoded(SnapshotPublication(publicationId, watermark, flags and CLOSED_FLAG.toInt() != 0), payload)
    }

    public fun isEnvelope(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
}
