package io.mazewall.profiler.attribution

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Bounded streaming encoder for [StackDefinition] records.
 *
 * Separates wire transport serialization, pending queue bounding, and watermarking
 * from in-memory frame interning in [StackTraceDictionary].
 */
public class StackDictionaryStreamEncoder(
    private val maxPendingEntries: Int = DEFAULT_MAX_PENDING,
) {
    private val pending = ArrayDeque<StackDefinition>()
    private var watermarkedId: Long = 0L
    private var droppedEntries: Long = 0L

    public val pendingCount: Int
        get() = pending.size

    public val watermark: Long
        get() = watermarkedId

    public val droppedCount: Long
        get() = droppedEntries

    /**
     * Enqueues a stack definition for streaming.
     *
     * If the pending queue reaches [maxPendingEntries], the oldest un-emitted definition
     * is dropped to keep memory strictly bounded, and [droppedCount] is incremented.
     */
    public fun enqueue(definition: StackDefinition) {
        if (definition.stackTraceId.value <= watermarkedId) {
            return
        }
        if (pending.size >= maxPendingEntries) {
            pending.removeFirst()
            droppedEntries++
        }
        pending.addLast(definition)
    }

    /**
     * Drains up to [maxCount] definitions and serializes them into wire-compatible binary records.
     * Updates [watermark] to the highest successfully encoded stack trace ID.
     */
    public fun drainEncoded(maxCount: Int = Int.MAX_VALUE): ByteArray {
        if (pending.isEmpty() || maxCount <= 0) return ByteArray(0)
        val out = ByteArrayOutputStream()
        var count = 0
        while (pending.isNotEmpty() && count < maxCount) {
            val def = pending.removeFirst()
            val encodedRecord = encodeStackDefinition(def)
            out.write(encodedRecord)
            if (def.stackTraceId.value > watermarkedId) {
                watermarkedId = def.stackTraceId.value
            }
            count++
        }
        return out.toByteArray()
    }

    public companion object {
        public const val DEFAULT_MAX_PENDING: Int = 10_000
        private const val STACK_RECORD_TYPE: Byte = 1
        private const val QUALITY_COMPLETE: Byte = 0
        private const val QUALITY_TRUNCATED: Byte = 1
        private const val KIND_JAVA: Byte = 0
        private const val KIND_NATIVE: Byte = 1

        /**
         * Encodes a single [StackDefinition] into an AgentDefinitionFile-compatible record:
         * [type: 1B = 1][length: 4B LE][payload]
         */
        public fun encodeStackDefinition(definition: StackDefinition): ByteArray {
            val payloadOut = ByteArrayOutputStream()

            // id: 4B LE
            val idBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            idBuf.putInt(definition.stackTraceId.value.toInt())
            payloadOut.write(idBuf.array())

            // quality: 1B
            payloadOut.write(if (definition.captureQuality == StackCaptureQuality.COMPLETE) QUALITY_COMPLETE.toInt() else QUALITY_TRUNCATED.toInt())

            // frameCount: 2B LE
            val countBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            countBuf.putShort(definition.frames.size.toShort())
            payloadOut.write(countBuf.array())

            // frames
            for (frame in definition.frames) {
                writeString(payloadOut, frame.classLoaderIdentity)
                writeString(payloadOut, frame.className)
                writeString(payloadOut, frame.methodName)
                writeString(payloadOut, frame.descriptor)

                val bciBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                bciBuf.putLong(frame.bytecodeLocation)
                payloadOut.write(bciBuf.array())

                payloadOut.write(if (frame.kind == ManagedFrameKind.JAVA) KIND_JAVA.toInt() else KIND_NATIVE.toInt())
            }

            val payload = payloadOut.toByteArray()

            // Header: 1B type + 4B length LE
            val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            header.put(STACK_RECORD_TYPE)
            header.putInt(payload.size)

            val fullRecord = ByteArrayOutputStream()
            fullRecord.write(header.array())
            fullRecord.write(payload)
            return fullRecord.toByteArray()
        }

        private fun writeString(
            out: ByteArrayOutputStream,
            str: String,
        ) {
            val bytes = str.toByteArray(StandardCharsets.UTF_8)
            val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            lenBuf.putShort(bytes.size.toShort())
            out.write(lenBuf.array())
            out.write(bytes)
        }
    }
}
