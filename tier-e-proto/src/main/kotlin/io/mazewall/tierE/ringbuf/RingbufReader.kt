package io.mazewall.tierE.ringbuf

import io.mazewall.tierE.ffi.PosixFfi
import java.lang.foreign.MemorySegment

/**
 * Userspace consumer for the kernel BPF ring buffer (kernel ≥5.8 layout):
 * one meta page holding `prod_pos`/`consumer_pos`, followed by the data area.
 * Single consumer thread; records may wrap the data ring, so payloads are
 * copied in up to two segments before parsing. Dropped/reserved-by-producer
 * records are simply not yet visible here — producer-side drop counters are
 * authoritative for loss accounting (WP-06).
 */
public class RingbufReader(
    ringFd: Int,
    private val dataLength: Long, // must equal round_page(max_entries)
    private val posix: PosixFfi = PosixFfi(),
    private val onEvent: (syscallNr: Int, contextId: UInt) -> Unit,
) : AutoCloseable {

    public companion object {
        private const val PAGE = 4096L
        private const val BUSY_BIT = 1L shl 30
        private const val DISCARD_BIT = 1L shl 31
        private const val LEN_MASK = 0x3FFFFFFFL
        private const val RECORD_SIZE = 24
    }

    private val mapping: MemorySegment = posix.mmapShared(PAGE + dataLength, ringFd)
    private val dataOffset: Long = PAGE
    private val mask: Long = dataLength - 1
    private var closed = false

    /** Drains whatever is visible; returns number of attributed events handled. */
    public fun pollOnce(): Int {
        if (closed) return 0
        var handled = 0
        val prodPos = readProd()
        var consPos = readCons()
        while (consPos < prodPos) {
            val recOff = dataOffset + (consPos and mask)
            val header = readU64(recOff)
            if ((header and BUSY_BIT) != 0L) break // producer still writing
            val len = (header and LEN_MASK).toInt()
            if (len < 0 || len > dataLength - 8) break // corrupt; refuse to advance blindly
            val payloadOff = recOff + 8
            if ((header and DISCARD_BIT) == 0L && len >= RECORD_SIZE) {
                val bytes = ByteArray(RECORD_SIZE)
                copyWrapped(payloadOff, bytes)
                // context_event { u64 ktime; u32 tgid; u32 tid; s32 nr; u32 ctx }
                var nr = 0
                for (i in 3 downTo 0) nr = (nr shl 8) or (bytes[16 + i].toInt() and 0xFF)
                var ctx = 0u
                for (i in 3 downTo 0) ctx = (ctx shl 8) or (bytes[20 + i].toUInt() and 0xFFu)
                onEvent(nr, ctx)
                handled++
            }
            consPos += 8 + align8(len)
            writeCons(consPos)
        }
        return handled
    }

    override fun close() {
        if (!closed) {
            posix.munmap(mapping, PAGE + dataLength)
            closed = true
        }
    }

    private fun copyWrapped(offset: Long, into: ByteArray) {
        val first = minOf(into.size.toLong(), dataOffset + dataLength - offset).toInt()
        MemorySegment.ofArray(into).asSlice(0, first.toLong())
            .copyFrom(mapping.asSlice(offset, first.toLong()))
        if (first < into.size) {
            MemorySegment.ofArray(into).asSlice(first.toLong(), (into.size - first).toLong())
                .copyFrom(mapping.asSlice(dataOffset, (into.size - first).toLong()))
        }
    }

    // Meta-page positions and record headers are 8-byte aligned within the
    // mapping, so native-endian long reads are safe on our supported arches.
    private val LONG = java.lang.foreign.ValueLayout.JAVA_LONG

    private fun readU64(off: Long): Long = mapping.get(LONG, off)

    private fun readProd(): Long = mapping.get(LONG, 0)

    private fun readCons(): Long = mapping.get(LONG, 8)

    private fun writeCons(value: Long) {
        mapping.set(LONG, 8, value)
    }

    private fun align8(v: Int): Int = (v + 7) and (Int.MAX_VALUE - 7)
}
