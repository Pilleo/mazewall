package io.mazewall.profiler.attribution

import io.mazewall.profiler.tierE.TierERetainedStateBudget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Publication metadata carried outside the unchanged MZSD v2 record stream. */
public data class SnapshotPublication(
    public val publicationId: Long,
    public val watermark: Long,
    public val closed: Boolean,
    public val sessionEpoch: SessionEpoch? = null,
) {
    init {
        require(publicationId >= 0) { "publication id must not be negative" }
        require(watermark >= 0) { "snapshot watermark must not be negative" }
    }
}

/** Versioned outer envelope for live MZSD snapshots. */
public object SnapshotEnvelope {
    private val MAGIC = "MZSE".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION_1: Short = 1
    private const val VERSION_2: Short = 2
    private const val CLOSED_FLAG: Short = 1
    private const val HEADER_SIZE_V1 = 32
    private const val HEADER_SIZE_V2 = 40
    private const val REMAINING_HEADER_BYTES_V1 = 24
    private const val REMAINING_HEADER_BYTES_V2 = 32

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
        sessionEpoch: SessionEpoch? = null,
    ): ByteArray {
        require(publicationId >= 0) { "publication id must not be negative" }
        require(watermark >= 0) { "snapshot watermark must not be negative" }
        require(payload.size.toLong() <= MAX_PAYLOAD_BYTES) {
            "snapshot payload exceeds bounded retained-state budget"
        }
        val version = if (sessionEpoch == null) VERSION_1 else VERSION_2
        val headerSize = if (sessionEpoch == null) HEADER_SIZE_V1 else HEADER_SIZE_V2
        val output = ByteBuffer.allocate(headerSize + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        output.put(MAGIC)
        output.putShort(version)
        output.putShort(if (closed) CLOSED_FLAG else 0)
        sessionEpoch?.let {
            require(it.value > 0) { "snapshot session epoch must be positive" }
            output.putLong(it.value)
        }
        output.putLong(publicationId)
        output.putLong(watermark)
        output.putLong(payload.size.toLong())
        output.put(payload)
        return output.array()
    }

    public fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= HEADER_SIZE_V1) { "snapshot envelope is shorter than its header" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(input::get)
        require(magic.contentEquals(MAGIC)) { "invalid snapshot envelope magic" }
        val version = input.short
        require(version == VERSION_1 || version == VERSION_2) { "unsupported snapshot envelope version" }
        require(input.remaining() >= if (version == VERSION_2) REMAINING_HEADER_BYTES_V2 else REMAINING_HEADER_BYTES_V1) {
            "snapshot envelope is shorter than its header"
        }
        val flags = input.short.toInt() and 0xffff
        require(flags and CLOSED_FLAG.toInt() == flags) { "unknown snapshot envelope flags" }
        val sessionEpoch = if (version == VERSION_2) {
            SessionEpoch(input.long).also { require(it.value > 0) { "snapshot session epoch is not positive" } }
        } else {
            null
        }
        val publicationId = input.long
        val watermark = input.long
        val payloadLength = input.long
        require(publicationId >= 0) { "snapshot publication id is negative" }
        require(watermark >= 0) { "snapshot watermark is negative" }
        require(
            payloadLength >= 0 &&
                payloadLength <= MAX_PAYLOAD_BYTES &&
                payloadLength <= Int.MAX_VALUE &&
                payloadLength == input.remaining().toLong(),
        ) {
            "invalid snapshot payload length $payloadLength"
        }
        val payload = ByteArray(payloadLength.toInt()).also(input::get)
        require(!input.hasRemaining()) { "snapshot envelope has trailing bytes" }
        return Decoded(
            SnapshotPublication(
                publicationId,
                watermark,
                flags and CLOSED_FLAG.toInt() != 0,
                sessionEpoch,
            ),
            payload,
        )
    }

    public fun isEnvelope(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private const val MAX_PAYLOAD_BYTES = TierERetainedStateBudget.DEFAULT_LIMIT_BYTES
}
