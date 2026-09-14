package io.mazewall.profiler.internal.attribution

import io.mazewall.profiler.tierE.TierERetainedStateBudget
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** A prepared dictionary payload whose queue ownership is settled by commit or rollback. */
internal class EncodedDictionaryBatch internal constructor(
    bytes: ByteArray,
    definitions: List<StackDefinition>,
    public val batchId: Long,
    public val proposedWatermark: Long,
) {
    private val encodedBytes: ByteArray = bytes.copyOf()
    public val bytes: ByteArray
        get() = encodedBytes.copyOf()
    public val definitions: List<StackDefinition> = java.util.List.copyOf(definitions)
}

/**
 * Bounded streaming encoder for [StackDefinition] records.
 *
 * Separates wire transport serialization, pending queue bounding, and watermarking
 * from in-memory frame interning in [StackTraceDictionary].
 */
internal class StackDictionaryStreamEncoder(
    private val maxPendingEntries: Int = DEFAULT_MAX_PENDING,
) {
    private val pending = ArrayDeque<StackDefinition>()
    private var watermarkedId: Long = 0L
    private var droppedEntries: Long = 0L
    private var inFlight: EncodedDictionaryBatch? = null
    private var retryBatch: EncodedDictionaryBatch? = null
    private var nextBatchId: Long = 1L
    private var retainedBudget: TierERetainedStateBudget? = null
    private val retainedBytes = java.util.IdentityHashMap<StackDefinition, Long>()

    /** Attaches the session-wide retained-state budget before definitions are enqueued. */
    @Synchronized
    internal fun attachRetainedBudget(budget: TierERetainedStateBudget) {
        check(retainedBudget == null) { "retained-state budget is already attached" }
        check(pending.isEmpty()) { "retained-state budget must be attached before enqueue" }
        retainedBudget = budget
    }

    @get:Synchronized
    public val pendingCount: Int
        get() = pending.size

    @get:Synchronized
    public val watermark: Long
        get() = watermarkedId

    @get:Synchronized
    public val droppedCount: Long
        get() = droppedEntries

    /** True when a prepared batch must be committed or rolled back before another is prepared. */
    @get:Synchronized
    public val hasInFlightBatch: Boolean
        get() = inFlight != null

    /**
     * Enqueues a stack definition for streaming.
     *
     * If the pending queue reaches [maxPendingEntries], the oldest un-emitted definition
     * is dropped to keep memory strictly bounded, and [droppedCount] is incremented.
     */
    @Synchronized
    public fun enqueue(definition: StackDefinition) {
        if (definition.stackTraceId.value <= watermarkedId) {
            return
        }
        val encodedSize = encodeStackDefinition(definition).size.toLong()
        val budget = retainedBudget
        if (
            budget != null &&
            !budget.tryReserve(TierERetainedStateBudget.Category.DICTIONARY_STAGING, encodedSize)
        ) {
            droppedEntries++
            return
        }
        if (pending.size >= maxPendingEntries) {
            val protectedCount = inFlight?.definitions?.size ?: 0
            if (pending.size == protectedCount) {
                retainedBudget?.release(TierERetainedStateBudget.Category.DICTIONARY_STAGING, encodedSize)
                droppedEntries++
                return
            }
            val retained = pending.toMutableList()
            val removed = retained.removeAt(protectedCount)
            pending.clear()
            pending.addAll(retained)
            releaseRetained(removed)
            droppedEntries++
        }
        pending.addLast(definition)
        retainedBytes[definition] = encodedSize
    }

    /**
     * Prepares an ordered batch without removing it from the pending queue.
     *
     * The caller must call [commit] after successful sink delivery or [rollback] after failure.
     * Only one batch may be outstanding at a time.
     */
    @Synchronized
    public fun prepareBatch(maxCount: Int = Int.MAX_VALUE): EncodedDictionaryBatch? {
        if (maxCount <= 0 || pending.isEmpty()) return null
        check(inFlight == null) { "a dictionary batch is already in flight" }
        retryBatch?.let { batch ->
            check(batch.definitions.size <= maxCount) { "retry batch exceeds requested batch size" }
            inFlight = batch
            return batch
        }
        val definitions = pending.take(maxCount)
        val batch = EncodedDictionaryBatch(
            bytes = definitions
                .fold(ByteArrayOutputStream()) { out, definition ->
                out.write(encodeStackDefinition(definition))
                out
            }.toByteArray(),
            batchId = nextBatchId++,
            definitions = definitions,
            proposedWatermark = maxOf(watermarkedId, definitions.maxOf { it.stackTraceId.value }),
        )
        inFlight = batch
        return batch
    }

    /** Commits a successfully delivered batch and advances the watermark. */
    @Synchronized
    public fun commit(batch: EncodedDictionaryBatch) {
        check(inFlight === batch) { "dictionary batch is not the current in-flight batch" }
        batch.definitions.forEach { expected ->
            check(pending.removeFirst() == expected) { "dictionary queue changed while batch was in flight" }
            releaseRetained(expected)
        }
        watermarkedId = batch.proposedWatermark
        inFlight = null
        retryBatch = null
    }

    /** Rolls back a failed delivery while preserving the batch and watermark for retry. */
    @Synchronized
    public fun rollback(batch: EncodedDictionaryBatch) {
        check(inFlight === batch) { "dictionary batch is not the current in-flight batch" }
        inFlight = null
        retryBatch = batch
    }

    /** Releases terminally abandoned staging after its evidence has been recorded. */
    @Synchronized
    internal fun abandon() {
        inFlight = null
        retryBatch = null
        while (pending.isNotEmpty()) releaseRetained(pending.removeFirst())
    }

    private fun releaseRetained(definition: StackDefinition) {
        val bytes = retainedBytes.remove(definition) ?: return
        retainedBudget?.release(TierERetainedStateBudget.Category.DICTIONARY_STAGING, bytes)
    }

    /**
     * Drains up to [maxCount] definitions and serializes them into wire-compatible binary records.
     * Updates [watermark] to the highest successfully encoded stack trace ID.
     */
    @Synchronized
    public fun drainEncoded(maxCount: Int = Int.MAX_VALUE): ByteArray {
        val batch = prepareBatch(maxCount) ?: return ByteArray(0)
        commit(batch)
        return batch.bytes
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
