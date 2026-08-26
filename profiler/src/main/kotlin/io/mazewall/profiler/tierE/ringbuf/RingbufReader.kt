package io.mazewall.profiler.tierE.ringbuf

import io.mazewall.profiler.tierE.ffi.PosixFfi
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Userspace consumer for the kernel BPF ring buffer (kernel ≥5.8).
 * Maps the ENTIRE region (meta page + data area) as ONE RW mapping
 * directly via Java FFM downcall — matching libbpf convention.
 */
public class RingbufReader(
    ringFd: Int,
    private val dataLength: Long,
    private val posix: PosixFfi = PosixFfi(),
    private val onEvent: (ContextEvent) -> Unit,
) : AutoCloseable {

    private val LONG = ValueLayout.JAVA_LONG
    private val mapping: MemorySegment =
        posix.mmapShared(PAGE + dataLength, ringFd)
    private val dataOffset: Long = PAGE
    private val mask: Long = dataLength - 1
    private var closed = false

    public fun pollOnce(): Int {
        if (closed) return 0
        var handled = 0
        var consPos: Long = mapping.get(LONG, CONS_POS_OFF)
        val prodPos: Long = mapping.get(LONG, PROD_POS_OFF)
        while (consPos < prodPos) {
            val recOff = dataOffset + (consPos and mask)
            val header = mapping.get(LONG, recOff)
            if ((header and BUSY_BIT) != 0L) break

            val payloadLen = (header and LEN_MASK).toInt()
            val discarded = (header and DISCARD_BIT) != 0L
            if (payloadLen < 0 || payloadLen > (dataLength - HEADER_SIZE).toInt()) break

            if (!discarded && payloadLen >= ContextEvent.SIZE_BYTES) {
                val event = readEventAt(recOff + HEADER_SIZE)
                onEvent(event)
                handled++
            }
            consPos += HEADER_SIZE + align8(payloadLen)
            mapping.set(LONG, CONS_POS_OFF, consPos)
        }
        return handled
    }

    private fun readEventAt(payloadOffset: Long): ContextEvent {
        val bytes = ByteArray(ContextEvent.SIZE_BYTES)
        val first = minOf(bytes.size.toLong(), dataOffset + dataLength - payloadOffset).toInt()
        MemorySegment.copy(mapping, payloadOffset, MemorySegment.ofArray(bytes), 0L, first.toLong())
        if (first < bytes.size) {
            MemorySegment.copy(mapping, dataOffset, MemorySegment.ofArray(bytes), first.toLong(), (bytes.size - first).toLong())
        }
        return ContextEvent.fromBytes(bytes)
    }

    override fun close() {
        if (!closed) {
            posix.munmap(mapping, PAGE + dataLength)
            closed = true
        }
    }

    public companion object {
        const val PROD_POS_OFF = 0L
        const val CONS_POS_OFF = 8L
        const val HEADER_SIZE = 8L
        const val PAGE = 4096L
        const val BUSY_BIT = 1L shl 30
        const val DISCARD_BIT = 1L shl 31
        const val LEN_MASK = 0x3FFFFFFFL
        fun align8(v: Int): Int = (v + 7) and (Int.MAX_VALUE - 7)
    }
}
