package io.mazewall.portal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Length-prefixed binary encoding for generated portal stubs.
 * Built-in ECHO/CHECKSUM frames stay raw; do not mix the two layouts.
 */
public object PortalCodec {
    public fun encodeBoolean(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    public fun encodeByte(value: Byte): ByteArray = byteArrayOf(value)

    public fun encodeShort(value: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value).array()

    public fun encodeInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    public fun encodeLong(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

    public fun encodeFloat(value: Float): ByteArray = encodeInt(value.toRawBits())

    public fun encodeDouble(value: Double): ByteArray = encodeLong(value.toRawBits())

    public fun encodeChar(value: Char): ByteArray = encodeShort(value.code.toShort())

    public fun encodeString(value: String): ByteArray = encodeBytes(value.toByteArray(StandardCharsets.UTF_8))

    public fun encodeBytes(value: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(4 + value.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(value.size)
        out.put(value)
        return out.array()
    }

    public fun concat(parts: List<ByteArray>): ByteArray {
        val size = parts.sumOf { it.size }
        val out = ByteArray(size)
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
            val v = ByteBuffer.wrap(buf, pos, 2).order(ByteOrder.BIG_ENDIAN).short
            pos += 2
            return v
        }

        public fun int(): Int {
            val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            pos += 4
            return v
        }

        public fun long(): Long {
            val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.BIG_ENDIAN).long
            pos += 8
            return v
        }

        public fun float(): Float = Float.fromBits(int())

        public fun double(): Double = Double.fromBits(long())

        public fun char(): Char = short().toInt().toChar()

        public fun bytes(): ByteArray {
            val n = int()
            require(n >= 0 && pos + n <= buf.size) { "portal codec underrun" }
            val slice = buf.copyOfRange(pos, pos + n)
            pos += n
            return slice
        }

        public fun string(): String = bytes().toString(StandardCharsets.UTF_8)
    }
}
