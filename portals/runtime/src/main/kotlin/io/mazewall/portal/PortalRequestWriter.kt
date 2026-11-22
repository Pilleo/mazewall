package io.mazewall.portal

import java.io.InputStream

/**
 * Bounded single-buffer binary encoder matching [PortalCodec] layouts.
 * Enforces byte, collection-cardinality, and aggregate traversal budgets using checked
 * arithmetic before buffer allocation, growth, or unnecessary element traversal.
 */
public class PortalRequestWriter
    @JvmOverloads
    constructor(
        public val maxBytes: Int = PortalFrame.MAX_PAYLOAD,
        public val maxCollectionElements: Int = PortalCodec.MAX_COLLECTION_ELEMENTS,
        public val maxTotalElements: Int = DEFAULT_MAX_TOTAL_ELEMENTS,
    ) {
    init {
        require(maxBytes in 0..PortalFrame.MAX_PAYLOAD) {
            "portal writer maxBytes $maxBytes exceeds protocol bound ${PortalFrame.MAX_PAYLOAD}"
        }
        require(maxCollectionElements in 0..PortalCodec.MAX_COLLECTION_ELEMENTS) {
            "portal writer maxCollectionElements $maxCollectionElements exceeds ${PortalCodec.MAX_COLLECTION_ELEMENTS}"
        }
        require(maxTotalElements >= 0) { "portal writer maxTotalElements $maxTotalElements must not be negative" }
    }

    private var buffer: ByteArray = EMPTY_BYTES
    private var position: Int = 0
    private var finished: Boolean = false
    private var highWaterCapacity: Int = 0
    private var growths: Int = 0
    private var peakBytes: Long = 0L
    private var totalElementsVisited: Int = 0

    public val writtenBytes: Int
        get() = position

    internal val bufferCapacity: Int
        get() = highWaterCapacity

    internal val growthCount: Int
        get() = growths

    internal val peakAllocatedBytes: Long
        get() = peakBytes

    internal val visitedElements: Int
        get() = totalElementsVisited

    public fun boolean(value: Boolean) {
        byte(if (value) 1 else 0)
    }

    public fun byte(value: Byte) {
        ensureCapacity(1)
        buffer[position++] = value
    }

    public fun short(value: Short) {
        ensureCapacity(SHORT_BYTES)
        writeShortAt(buffer, position, value)
        position += SHORT_BYTES
    }

    public fun int(value: Int) {
        ensureCapacity(INT_BYTES)
        writeIntAt(buffer, position, value)
        position += INT_BYTES
    }

    public fun long(value: Long) {
        ensureCapacity(LONG_BYTES)
        writeIntAt(buffer, position, (value ushr LONG_HIGH_SHIFT).toInt())
        writeIntAt(buffer, position + INT_BYTES, value.toInt())
        position += LONG_BYTES
    }

    public fun float(value: Float) {
        int(value.toRawBits())
    }

    public fun double(value: Double) {
        long(value.toRawBits())
    }

    public fun char(value: Char) {
        short(value.code.toShort())
    }

    public fun bytes(value: ByteArray) {
        checkOpen()
        val total = INT_BYTES.toLong() + value.size.toLong()
        if (total > remainingBytes().toLong()) {
            limitExceeded("encoded byte array requires $total bytes, exceeding remaining budget of ${remainingBytes()} bytes")
        }
        ensureCapacity(total.toInt())
        writeIntAt(buffer, position, value.size)
        position += INT_BYTES
        value.copyInto(buffer, position)
        position += value.size
    }

    public fun string(value: String) {
        checkOpen()
        val lowerBound = INT_BYTES.toLong() + value.length.toLong()
        if (lowerBound > remainingBytes().toLong()) {
            limitExceeded("encoded string requires at least $lowerBound bytes, exceeding remaining budget of ${remainingBytes()} bytes")
        }
        val maxDataBytes = remainingBytes() - INT_BYTES
        val utf8Bytes = measureUtf8Bytes(value, maxDataBytes)
        if (utf8Bytes < 0) {
            limitExceeded("encoded string UTF-8 length exceeds remaining budget of $maxDataBytes bytes")
        }
        ensureCapacity(INT_BYTES + utf8Bytes)
        writeIntAt(buffer, position, utf8Bytes)
        position += INT_BYTES
        val written = writeUtf8(value, buffer, position)
        check(written == utf8Bytes) { "UTF-8 encoded length mismatch: expected $utf8Bytes, wrote $written" }
        position += utf8Bytes
    }

    public fun inputSnapshot(
        source: InputStream,
        maximumBytes: Int,
    ) {
        require(maximumBytes >= 0) { "portal input snapshot maximumBytes must not be negative" }
        ensureCapacity(INT_BYTES)
        val lengthOffset = position
        position += INT_BYTES
        val remainingForData = remainingBytes()
        val chunk = ByteArray(minOf(maxOf(minOf(maximumBytes, remainingForData), 1), SNAPSHOT_SCRATCH_BYTES))
        peakBytes = maxOf(peakBytes, buffer.size.toLong() + chunk.size.toLong())
        var written = 0
        var read = source.read(chunk)
        while (read >= 0) {
            if (read > 0) {
                require(written <= maximumBytes - read) {
                    "portal input snapshot exceeds configured limit of $maximumBytes bytes"
                }
                if (written > remainingForData - read) {
                    limitExceeded(
                        "encoded input snapshot exceeds remaining transport budget of $remainingForData bytes",
                    )
                }
                ensureCapacity(read)
                peakBytes = maxOf(peakBytes, buffer.size.toLong() + chunk.size.toLong())
                chunk.copyInto(buffer, position, 0, read)
                position += read
                written += read
            }
            read = source.read(chunk)
        }
        writeIntAt(buffer, lengthOffset, written)
    }

    public fun <T> nullable(
        value: T?,
        encodeValue: (T) -> Unit,
    ) {
        if (value == null) {
            boolean(false)
        } else {
            boolean(true)
            encodeValue(value)
        }
    }

    public fun <T> list(
        values: List<T>,
        encodeElement: (T) -> Unit,
    ) {
        val declaredSize = beginCollection(values.size, minBytesPerElement = 1L, kindLabel = "list")
        int(declaredSize)
        var actual = 0
        val iterator = values.iterator()
        while (iterator.hasNext()) {
            if (actual >= declaredSize) {
                limitExceeded("list grew past declared size $declaredSize during portal encoding")
            }
            if (totalElementsVisited >= maxTotalElements) {
                limitExceeded("portal aggregate element count exceeds budget $maxTotalElements")
            }
            actual++
            totalElementsVisited++
            encodeElement(iterator.next())
        }
        if (actual != declaredSize) {
            limitExceeded("list yielded $actual elements, expected declared size $declaredSize")
        }
    }

    public fun <K, V> map(
        values: Map<K, V>,
        encodeKey: (K) -> Unit,
        encodeValue: (V) -> Unit,
    ) {
        val declaredSize = beginCollection(values.size, minBytesPerElement = MIN_MAP_ENTRY_BYTES, kindLabel = "map")
        int(declaredSize)
        var actual = 0
        val iterator = values.entries.iterator()
        while (iterator.hasNext()) {
            if (actual >= declaredSize) {
                limitExceeded("map grew past declared size $declaredSize during portal encoding")
            }
            if (totalElementsVisited >= maxTotalElements) {
                limitExceeded("portal aggregate element count exceeds budget $maxTotalElements")
            }
            actual++
            totalElementsVisited++
            val entry = iterator.next()
            encodeKey(entry.key)
            encodeValue(entry.value)
        }
        if (actual != declaredSize) {
            limitExceeded("map yielded $actual entries, expected declared size $declaredSize")
        }
    }

    public fun toByteArray(): ByteArray {
        checkOpen()
        finished = true
        if (position == 0) {
            buffer = EMPTY_BYTES
            return EMPTY_BYTES
        }
        if (position == buffer.size) {
            val exact = buffer
            buffer = EMPTY_BYTES
            return exact
        }
        peakBytes = maxOf(peakBytes, buffer.size.toLong() + position.toLong())
        val trimmed = buffer.copyOf(position)
        buffer = EMPTY_BYTES
        return trimmed
    }

    private fun beginCollection(
        declaredSize: Int,
        minBytesPerElement: Long,
        kindLabel: String,
    ): Int {
        checkOpen()
        if (declaredSize !in 0..maxCollectionElements) {
            limitExceeded("portal $kindLabel count $declaredSize exceeds limit $maxCollectionElements")
        }
        val remainingElements = (maxTotalElements - totalElementsVisited).toLong()
        if (declaredSize.toLong() > remainingElements) {
            limitExceeded("portal aggregate element count would exceed budget $maxTotalElements")
        }
        val minBytesRequired = INT_BYTES.toLong() + declaredSize.toLong() * minBytesPerElement
        if (minBytesRequired > remainingBytes().toLong()) {
            limitExceeded("encoded $kindLabel requires at least $minBytesRequired bytes, exceeding remaining budget of ${remainingBytes()} bytes")
        }
        return declaredSize
    }

    private fun ensureCapacity(additional: Int) {
        checkOpen()
        require(additional >= 0) { "negative encoded length $additional" }
        val required = position.toLong() + additional.toLong()
        if (required > maxBytes.toLong()) {
            limitExceeded("encoded portal payload requires $required bytes, exceeding limit of $maxBytes bytes")
        }
        if (required > buffer.size.toLong()) {
            val doubled = buffer.size.toLong() * 2L
            val target = minOf(maxBytes.toLong(), maxOf(required, maxOf(doubled, DEFAULT_INITIAL_CAPACITY.toLong()))).toInt()
            peakBytes = maxOf(peakBytes, buffer.size.toLong() + target.toLong())
            buffer = if (buffer.isEmpty()) ByteArray(target) else buffer.copyOf(target)
            highWaterCapacity = maxOf(highWaterCapacity, target)
            growths++
        }
    }

    private fun checkOpen() {
        check(!finished) { "portal request writer is already finished" }
    }

    private fun remainingBytes(): Int = maxBytes - position

    public companion object {
        public const val DEFAULT_MAX_TOTAL_ELEMENTS: Int = 262_144
        private const val DEFAULT_INITIAL_CAPACITY: Int = 256
        private const val SNAPSHOT_SCRATCH_BYTES: Int = 8_192
        private const val SHORT_BYTES: Int = 2
        private const val INT_BYTES: Int = 4
        private const val LONG_BYTES: Int = 8
        private const val LONG_HIGH_SHIFT: Int = 32
        private const val MIN_MAP_ENTRY_BYTES: Long = 2L
        private val EMPTY_BYTES: ByteArray = ByteArray(0)
        private val replyByteLimit: ThreadLocal<Int?> = ThreadLocal()

        public fun forReply(): PortalRequestWriter = PortalRequestWriter(maxBytes = replyByteLimit.get() ?: PortalFrame.MAX_PAYLOAD)

        public fun <T> withReplyLimit(
            maxBytes: Int,
            block: () -> T,
        ): T {
            require(maxBytes in 0..PortalFrame.MAX_PAYLOAD) {
                "portal reply limit $maxBytes exceeds protocol bound ${PortalFrame.MAX_PAYLOAD}"
            }
            val previous = replyByteLimit.get()
            replyByteLimit.set(maxBytes)
            return try {
                block()
            } finally {
                if (previous == null) replyByteLimit.remove() else replyByteLimit.set(previous)
            }
        }
    }
}

private const val BYTE_MASK: Int = 0xff
private const val SHORT_HIGH_SHIFT: Int = 8
private const val INT_SHIFT_24: Int = 24
private const val INT_SHIFT_16: Int = 16
private const val INT_SHIFT_8: Int = 8
private const val UTF8_1_BYTE_MAX: Int = 0x7f
private const val UTF8_2_BYTE_MAX: Int = 0x7ff
private const val UTF8_3_BYTE_LEN: Int = 3
private const val UTF8_4_BYTE_LEN: Int = 4
private const val UTF8_2_PREFIX: Int = 0xc0
private const val UTF8_3_PREFIX: Int = 0xe0
private const val UTF8_4_PREFIX: Int = 0xf0
private const val UTF8_CONT_PREFIX: Int = 0x80
private const val UTF8_CONT_MASK: Int = 0x3f
private const val UTF8_SHIFT_6: Int = 6
private const val UTF8_SHIFT_12: Int = 12
private const val UTF8_SHIFT_18: Int = 18
private const val UTF8_REPLACEMENT_BYTE: Byte = 0x3f

private fun limitExceeded(message: String): Nothing = throw PortalCallException(message, PortalInvocationEvidence.NOT_STARTED)

private fun writeShortAt(
    target: ByteArray,
    offset: Int,
    value: Short,
) {
    val raw = value.toInt()
    target[offset] = ((raw ushr SHORT_HIGH_SHIFT) and BYTE_MASK).toByte()
    target[offset + 1] = (raw and BYTE_MASK).toByte()
}

private fun writeIntAt(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    var index = offset
    target[index++] = ((value ushr INT_SHIFT_24) and BYTE_MASK).toByte()
    target[index++] = ((value ushr INT_SHIFT_16) and BYTE_MASK).toByte()
    target[index++] = ((value ushr INT_SHIFT_8) and BYTE_MASK).toByte()
    target[index] = (value and BYTE_MASK).toByte()
}

private fun measureUtf8Bytes(
    value: String,
    maxDataBytes: Int,
): Int {
    var total = 0
    var i = 0
    val len = value.length
    while (i < len) {
        val ch = value[i]
        val delta = when {
            ch.code <= UTF8_1_BYTE_MAX -> {
                i++
                1
            }
            ch.code <= UTF8_2_BYTE_MAX -> {
                i++
                2
            }
            Character.isHighSurrogate(ch) && i + 1 < len && Character.isLowSurrogate(value[i + 1]) -> {
                i += 2
                UTF8_4_BYTE_LEN
            }
            ch.isSurrogate() -> {
                i++
                1
            }
            else -> {
                i++
                UTF8_3_BYTE_LEN
            }
        }
        if (delta > maxDataBytes - total) return -1
        total += delta
    }
    return total
}

private fun writeUtf8(
    value: String,
    target: ByteArray,
    startOffset: Int,
): Int {
    var out = startOffset
    var i = 0
    val len = value.length
    while (i < len) {
        val ch = value[i]
        when {
            ch.code <= UTF8_1_BYTE_MAX -> {
                target[out++] = ch.code.toByte()
                i++
            }
            ch.code <= UTF8_2_BYTE_MAX -> {
                val code = ch.code
                target[out++] = (UTF8_2_PREFIX or (code ushr UTF8_SHIFT_6)).toByte()
                target[out++] = (UTF8_CONT_PREFIX or (code and UTF8_CONT_MASK)).toByte()
                i++
            }
            Character.isHighSurrogate(ch) && i + 1 < len && Character.isLowSurrogate(value[i + 1]) -> {
                val cp = Character.toCodePoint(ch, value[i + 1])
                target[out++] = (UTF8_4_PREFIX or (cp ushr UTF8_SHIFT_18)).toByte()
                target[out++] = (UTF8_CONT_PREFIX or ((cp ushr UTF8_SHIFT_12) and UTF8_CONT_MASK)).toByte()
                target[out++] = (UTF8_CONT_PREFIX or ((cp ushr UTF8_SHIFT_6) and UTF8_CONT_MASK)).toByte()
                target[out++] = (UTF8_CONT_PREFIX or (cp and UTF8_CONT_MASK)).toByte()
                i += 2
            }
            ch.isSurrogate() -> {
                target[out++] = UTF8_REPLACEMENT_BYTE
                i++
            }
            else -> {
                val code = ch.code
                target[out++] = (UTF8_3_PREFIX or (code ushr UTF8_SHIFT_12)).toByte()
                target[out++] = (UTF8_CONT_PREFIX or ((code ushr UTF8_SHIFT_6) and UTF8_CONT_MASK)).toByte()
                target[out++] = (UTF8_CONT_PREFIX or (code and UTF8_CONT_MASK)).toByte()
                i++
            }
        }
    }
    return out - startOffset
}
