package io.mazewall.portal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Length-prefixed binary encoding for generated portal stubs.
 * Built-in ECHO/CHECKSUM frames stay raw; do not mix the two layouts.
 */
public object PortalCodec {
    private const val MAX_COLLECTION_ELEMENTS: Int = 16_384
    private const val SHORT_BYTES: Int = 2
    private const val INT_BYTES: Int = 4
    private const val LONG_BYTES: Int = 8

    public fun encodeBoolean(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    public fun encodeByte(value: Byte): ByteArray = byteArrayOf(value)

    public fun encodeShort(value: Short): ByteArray =
        ByteBuffer
        .allocate(SHORT_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putShort(value)
        .array()

    public fun encodeInt(value: Int): ByteArray =
        ByteBuffer
        .allocate(INT_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value)
        .array()

    public fun encodeLong(value: Long): ByteArray =
        ByteBuffer
        .allocate(LONG_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(value)
        .array()

    public fun encodeFloat(value: Float): ByteArray = encodeInt(value.toRawBits())

    public fun encodeDouble(value: Double): ByteArray = encodeLong(value.toRawBits())

    public fun encodeChar(value: Char): ByteArray = encodeShort(value.code.toShort())

    public fun encodeString(value: String): ByteArray = encodeBytes(value.toByteArray(StandardCharsets.UTF_8))

    public fun encodeBytes(value: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(INT_BYTES + value.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(value.size)
        out.put(value)
        return out.array()
    }

    public fun concat(parts: List<ByteArray>): ByteArray {
        val totalSize = parts.sumOf { it.size.toLong() }
        require(totalSize in 0..Int.MAX_VALUE) { "total size $totalSize exceeds Int.MAX_VALUE" }
        val out = ByteArray(totalSize.toInt())
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    public class Reader(
        private val buf: ByteArray,
    ) {
        private var pos: Int = 0

        public fun boolean(): Boolean = byte().toInt() != 0

        public fun byte(): Byte {
            require(pos < buf.size) { "portal codec underrun" }
            return buf[pos++]
        }

        public fun short(): Short {
            requireRemaining(SHORT_BYTES)
            val v = ByteBuffer.wrap(buf, pos, SHORT_BYTES).order(ByteOrder.BIG_ENDIAN).short
            pos += SHORT_BYTES
            return v
        }

        public fun int(): Int {
            requireRemaining(INT_BYTES)
            val v = ByteBuffer.wrap(buf, pos, INT_BYTES).order(ByteOrder.BIG_ENDIAN).int
            pos += INT_BYTES
            return v
        }

        public fun long(): Long {
            requireRemaining(LONG_BYTES)
            val v = ByteBuffer.wrap(buf, pos, LONG_BYTES).order(ByteOrder.BIG_ENDIAN).long
            pos += LONG_BYTES
            return v
        }

        public fun float(): Float = Float.fromBits(int())

        public fun double(): Double = Double.fromBits(long())

        public fun char(): Char = short().toInt().toChar()

        public fun bytes(): ByteArray {
            val n = int()
            require(n in 0..PortalFrame.MAX_PAYLOAD && pos + n <= buf.size) { "portal codec underrun" }
            val slice = buf.copyOfRange(pos, pos + n)
            pos += n
            return slice
        }

        public fun string(): String = bytes().toString(StandardCharsets.UTF_8)

        /** Reads a bounded collection cardinality before decoding its members. */
        public fun collectionCount(): Int {
            val count = int()
            require(count in 0..MAX_COLLECTION_ELEMENTS) { "portal collection count $count exceeds $MAX_COLLECTION_ELEMENTS" }
            return count
        }

        /** True only after a decoder has consumed this payload exactly. */
        public fun exhausted(): Boolean = pos == buf.size

        private fun requireRemaining(size: Int) {
            require(pos + size <= buf.size) { "portal codec underrun" }
        }
    }
}
