package io.mazewall.profiler.tierE.ringbuf

import io.mazewall.LinuxNative.SyscallResult
import io.mazewall.RawSyscallOperations
import io.mazewall.core.NativeArg
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path

/** Deterministic userspace consumer for a Linux `BPF_MAP_TYPE_RINGBUF`. */
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
    private val mappings = mapBoth(mapFd)
    private val consumerAddress = mappings.first
    private val producerAddress = mappings.second
    private val consumer = MemorySegment.ofAddress(consumerAddress).reinterpret(pageSize, arena, null)
    private val producer = MemorySegment.ofAddress(producerAddress).reinterpret(producerMappingSize, arena, null)

    /** Returns all records committed at the instant the producer position is acquired. */
    fun drain(): List<ByteArray> {
        var position = LONG_HANDLE.getAcquire(consumer, 0L) as Long
        val producerPosition = LONG_HANDLE.getAcquire(producer, 0L) as Long
        val records = mutableListOf<ByteArray>()
        while (position < producerPosition) {
            val recordOffset = pageSize + (position and (capacity - 1))
            val lengthWord = INT_HANDLE.getAcquire(producer, recordOffset) as Int
            if ((lengthWord and BUSY_BIT) != 0) break
            val length = lengthWord and LENGTH_MASK
            check(length == validatedRecordSize) { "unexpected BPF ring record size $length (expected $validatedRecordSize)" }
            if ((lengthWord and DISCARD_BIT) == 0) {
                records += producer.asSlice(recordOffset + HEADER_SIZE, length.toLong()).toArray(ValueLayout.JAVA_BYTE)
            }
            position += alignedRecordSize(length)
        }
        LONG_HANDLE.setRelease(consumer, 0L, position)
        return records
    }

    override fun close() {
        try {
            unmap(producerAddress, producerMappingSize)
        } finally {
            try {
                unmap(consumerAddress, pageSize)
            } finally {
                arena.close()
            }
        }
    }

    private fun map(
        fd: Int,
        length: Long,
        protection: Int,
        offset: Long,
    ): Long {
        val result = native.syscall(
            SYS_MMAP,
            NativeArg.LongArg(0),
            NativeArg.LongArg(length),
            NativeArg.LongArg(protection.toLong()),
            NativeArg.LongArg(MAP_SHARED.toLong()),
            NativeArg.LongArg(fd.toLong()),
            NativeArg.LongArg(offset),
        )
        return when (result) {
            is SyscallResult.Success -> result.value.also { check(it != 0L) { "mmap returned NULL" } }
            is SyscallResult.Error -> result.throwErrno("mmap(BPF ring buffer)")
        }
    }

    private fun mapBoth(fd: Int): Pair<Long, Long> {
        val consumerMapping = map(fd, pageSize, PROT_READ or PROT_WRITE, 0)
        return try {
            consumerMapping to map(fd, producerMappingSize, PROT_READ, pageSize)
        } catch (failure: Throwable) {
            try {
                unmap(consumerMapping, pageSize)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            arena.close()
            throw failure
        }
    }

    private fun unmap(
        address: Long,
        length: Long,
    ) {
        when (val result = native.syscall(SYS_MUNMAP, NativeArg.LongArg(address), NativeArg.LongArg(length))) {
            is SyscallResult.Success -> Unit
            is SyscallResult.Error -> result.throwErrno("munmap(BPF ring buffer)")
        }
    }

    internal companion object {
        private const val SYS_MMAP = 9L
        private const val SYS_MUNMAP = 11L
        private const val PROT_READ = 1
        private const val PROT_WRITE = 2
        private const val MAP_SHARED = 1
        private const val HEADER_SIZE = 8L
        private const val BUSY_BIT = Int.MIN_VALUE
        private const val DISCARD_BIT = 1 shl 30
        private const val LENGTH_MASK = BUSY_BIT.inv() and DISCARD_BIT.inv()
        private val LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle()
        private val INT_HANDLE = ValueLayout.JAVA_INT.varHandle()

        internal fun alignedRecordSize(payloadSize: Int): Long = (payloadSize + HEADER_SIZE + 7) and -8L

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
