package io.mazewall.portal

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Length-prefixed binary encoding for generated portal stubs.
 * Built-in ECHO/CHECKSUM frames stay raw; do not mix the two layouts.
 */
public object PortalCodec {
    public const val MAX_COLLECTION_ELEMENTS: Int = 16_384
    private const val SHORT_BYTES: Int = 2
    private const val INT_BYTES: Int = 4
    private const val LONG_BYTES: Int = 8

    public fun requireCapabilityCount(
        actual: Int,
        expected: Int,
        context: String? = null,
    ) {
        if (actual != expected) {
            throw PortalMalformedPayloadException("expected $expected granted capabilities, received $actual", context)
        }
    }

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
        require(value.size <= Int.MAX_VALUE - INT_BYTES) {
            "byte array length ${value.size} overflows portal frame length"
        }
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

    public class Reader
        @JvmOverloads
        constructor(
            private val buf: ByteArray,
            context: String? = null,
        ) {
        private var pos: Int = 0
        private var currentContext: String? = context

        public fun <T> inContext(
            segment: String,
            block: () -> T,
        ): T {
            val previous = currentContext
            currentContext = if (previous.isNullOrBlank()) segment else "$previous.$segment"
            return try {
                block()
            } finally {
                currentContext = previous
            }
        }

        public fun boolean(): Boolean =
            when (byte().toInt()) {
                0 -> false
                1 -> true
                else -> throw malformed("noncanonical boolean byte")
            }

        public fun byte(): Byte {
            requireRemaining(1)
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
            if (n !in 0..PortalFrame.MAX_PAYLOAD || n > buf.size - pos) {
                throw malformed("portal codec underrun")
            }
            val slice = buf.copyOfRange(pos, pos + n)
            pos += n
            return slice
        }

        public fun string(): String {
            val raw = bytes()
            return try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString()
            } catch (invalid: CharacterCodingException) {
                throw malformed("malformed UTF-8 string", invalid)
            }
        }

        public fun uuid(): UUID =
            try {
                UUID.fromString(string())
            } catch (invalid: IllegalArgumentException) {
                throw malformed("malformed UUID value", invalid)
            }

        public fun instant(): Instant =
            try {
                Instant.parse(string())
            } catch (invalid: DateTimeParseException) {
                throw malformed("malformed Instant value", invalid)
            }

        public fun bigDecimal(): BigDecimal = string().toBigDecimalOrNull() ?: throw malformed("malformed BigDecimal value")

        public fun <E : Enum<E>> enumValue(enumClass: Class<E>): E =
            try {
                java.lang.Enum.valueOf(enumClass, string())
            } catch (invalid: IllegalArgumentException) {
                throw malformed("unknown enum constant for ${enumClass.name}", invalid)
            }

        public fun <K, V> map(
            decodeKey: () -> K,
            decodeValue: () -> V,
        ): Map<K, V> = mutableMap(decodeKey, decodeValue)

        public fun <K, V> mutableMap(
            decodeKey: () -> K,
            decodeValue: () -> V,
        ): MutableMap<K, V> {
            val count = collectionCount()
            val out = LinkedHashMap<K, V>(count)
            repeat(count) {
                val key = decodeKey()
                if (out.containsKey(key)) throw malformed("duplicate map key")
                out[key] = decodeValue()
            }
            return out
        }

        /** Reads a bounded collection cardinality before decoding its members. */
        public fun collectionCount(): Int {
            val count = int()
            if (count !in 0..MAX_COLLECTION_ELEMENTS) {
                throw malformed("portal collection count $count exceeds $MAX_COLLECTION_ELEMENTS")
            }
            return count
        }

        /** True only after a decoder has consumed this payload exactly. */
        public fun exhausted(): Boolean = pos == buf.size

        public fun requireExhausted() {
            if (pos != buf.size) {
                throw malformed("trailing payload bytes (${buf.size - pos} unconsumed)")
            }
        }

        private fun requireRemaining(size: Int) {
            if (size < 0 || size > buf.size - pos) {
                throw malformed("portal codec underrun")
            }
        }

        private fun malformed(
            message: String,
            cause: Throwable? = null,
        ): PortalMalformedPayloadException = PortalMalformedPayloadException(message, currentContext, cause)
    }
}
