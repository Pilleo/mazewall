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

    // Kernel contract on current kernels: the meta page (incl. consumer_pos)
    // is the ONLY writable mapping; the data area must be mapped READ-ONLY
    // through the page-offset alias. Verified empirically (errno=EPERM for
    // RW data) on kernel 7.1.4 — see WP-04 harness notes.
    private val metaRw: MemorySegment = posix.mmapShared(PAGE, ringFd)
    private val dataRo: MemorySegment = posix.mmapShared(dataLength, ringFd, posixProtRead(), PAGE)
    private val dataOffset: Long = 0
    private val mask: Long = dataLength - 1
    private var closed = false

    private fun posixProtRead(): Int = io.mazewall.tierE.ffi.PosixFfi.PROT_READ

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
            posix.munmap(dataRo, dataLength)
            posix.munmap(metaRw, PAGE)
            closed = true
        }
    }

    private fun copyWrapped(offset: Long, into: ByteArray) {
        val first = minOf(into.size.toLong(), dataLength - offset).toInt()
        java.lang.foreign.MemorySegment.copy(
            dataRo, offset, java.lang.foreign.MemorySegment.ofArray(into), 0L, first.toLong(),
        )
        if (first < into.size) {
            java.lang.foreign.MemorySegment.copy(
                dataRo, 0L,
                java.lang.foreign.MemorySegment.ofArray(into), first.toLong(),
                (into.size - first).toLong(),
            )
        }
    }

    // Meta-page positions and record headers are 8-byte aligned within the
    // mapping, so native-endian long reads are safe on our supported arches.
    private val LONG = java.lang.foreign.ValueLayout.JAVA_LONG

    private fun readU64(off: Long): Long = dataRo.get(LONG, off)

    private fun readProd(): Long = metaRw.get(LONG, 0)

    private fun readCons(): Long = metaRw.get(LONG, 8)

    private fun writeCons(value: Long) {
        metaRw.set(LONG, 8, value)
    }

    private fun align8(v: Int): Int = (v + 7) and (Int.MAX_VALUE - 7)
}
