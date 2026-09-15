package io.mazewall.profiler.tierE.ringbuf

import io.mazewall.platform.native.RawSyscallOperations
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path

/** Deterministic userspace consumer for a Linux `BPF_MAP_TYPE_RINGBUF`. */
internal enum class BpfRingBufferRecordKind { READY, BUSY, DISCARDED }

internal class BpfRingBufferReader(
    private val native: RawSyscallOperations,
    mapFd: Int,
    private val capacity: Long,
    private val recordSize: Int,
) : AutoCloseable {
    private val validatedCapacity = capacity.also {
        require(it > 0 && it.countOneBits() == 1) { "ring-buffer capacity must be a power of two" }
    }
    private val validatedRecordSize = recordSize.also { require(it > 0) { "record size must be positive" } }
    private val pageSize = linuxPageSize()
    private val arena = Arena.ofShared()
    private val producerMappingSize = pageSize + validatedCapacity * 2
    private val consumerMapping: NativeMapping
    private val producerMapping: NativeMapping
    private val consumer: MemorySegment
    private val producer: MemorySegment
    private var producerMapped = false
    private var consumerMapped = false
    private var arenaClosed = false

    init {
        val acquired = mapBoth(mapFd)
        consumerMapping = acquired.first
        producerMapping = acquired.second
        consumerMapped = true
        producerMapped = true
        try {
            consumer = MemorySegment.ofAddress(consumerMapping.address).reinterpret(pageSize, arena, null)
            producer = MemorySegment.ofAddress(producerMapping.address).reinterpret(producerMappingSize, arena, null)
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /** Returns all records committed at the instant the producer position is acquired. */
    fun drain(): List<ByteArray> {
        var position = LONG_HANDLE.getAcquire(consumer, 0L) as Long
        val producerPosition = LONG_HANDLE.getAcquire(producer, 0L) as Long
        val records = mutableListOf<ByteArray>()
        while (position < producerPosition) {
            val recordOffset = pageSize + (position and (capacity - 1))
            val lengthWord = INT_HANDLE.getAcquire(producer, recordOffset) as Int
            if (classify(lengthWord) == BpfRingBufferRecordKind.BUSY) break
            val length = lengthWord and LENGTH_MASK
            check(length == validatedRecordSize) { "unexpected BPF ring record size $length (expected $validatedRecordSize)" }
            if (classify(lengthWord) == BpfRingBufferRecordKind.READY) {
                records += producer.asSlice(recordOffset + HEADER_SIZE, length.toLong()).toArray(ValueLayout.JAVA_BYTE)
            }
            position += alignedRecordSize(length)
        }
        LONG_HANDLE.setRelease(consumer, 0L, position)
        return records
    }

    @Suppress("TooGenericExceptionCaught") // Each independent native mapping must be attempted and failed ownership retained for retry.
    @Synchronized
    override fun close() {
        var failure: Throwable? = null
        if (producerMapped) {
            try {
                producerMapping.close()
                producerMapped = false
            } catch (unmapFailure: Throwable) {
                failure = unmapFailure
            }
        }
        if (consumerMapped) {
            try {
                consumerMapping.close()
                consumerMapped = false
            } catch (unmapFailure: Throwable) {
                failure?.addSuppressed(unmapFailure) ?: run { failure = unmapFailure }
            }
        }
        if (!producerMapped && !consumerMapped && !arenaClosed) {
            try {
                arena.close()
                arenaClosed = true
            } catch (arenaFailure: Throwable) {
                failure?.addSuppressed(arenaFailure) ?: run { failure = arenaFailure }
            }
        }
        failure?.let { throw it }
    }

    private fun mapBoth(fd: Int): Pair<NativeMapping, NativeMapping> {
        val consumer = NativeMapping.map(native, fd, pageSize, METADATA_MAPPING_PROTECTION, 0)
        return try {
            consumer to NativeMapping.map(native, fd, producerMappingSize, DATA_MAPPING_PROTECTION, pageSize)
        } catch (expectedMappingFailure: Throwable) {
            try {
                consumer.close()
            } catch (expectedCleanupFailure: Throwable) {
                expectedMappingFailure.addSuppressed(expectedCleanupFailure)
            }
            try {
                arena.close()
                arenaClosed = true
            } catch (arenaFailure: Throwable) {
                expectedMappingFailure.addSuppressed(arenaFailure)
            }
            throw expectedMappingFailure
        }
    }

    internal companion object {
        private const val PROT_READ = 1
        private const val PROT_WRITE = 2
        private const val HEADER_SIZE = 8L
        internal const val BUSY_BIT = Int.MIN_VALUE
        internal const val DISCARD_BIT = 1 shl 30
        internal const val LENGTH_MASK = BUSY_BIT.inv() and DISCARD_BIT.inv()
        internal const val METADATA_MAPPING_PROTECTION = PROT_READ or PROT_WRITE
        internal const val DATA_MAPPING_PROTECTION = PROT_READ
        private val LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle()
        private val INT_HANDLE = ValueLayout.JAVA_INT.varHandle()

        internal fun classify(lengthWord: Int): BpfRingBufferRecordKind =
            when {
            lengthWord and BUSY_BIT != 0 -> BpfRingBufferRecordKind.BUSY
            lengthWord and DISCARD_BIT != 0 -> BpfRingBufferRecordKind.DISCARDED
            else -> BpfRingBufferRecordKind.READY
        }

        internal fun alignedRecordSize(payloadSize: Int): Long = (payloadSize + HEADER_SIZE + 7) and -8L

        internal fun nextPosition(
            position: Long,
            payloadSize: Int,
        ): Long = position + alignedRecordSize(payloadSize)

        private fun linuxPageSize(): Long {
            val line = Files
                .readAllLines(Path.of("/proc/self/smaps"))
                .firstOrNull { it.startsWith("KernelPageSize:") }
                ?: error("cannot determine Linux page size from /proc/self/smaps")
            return line
                .substringAfter(':')
                .trim()
                .substringBefore(' ')
                .toLong() *
                1024L
        }
    }
}
