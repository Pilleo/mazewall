package io.mazewall.tierE.ringbuf

import io.mazewall.tierE.ffi.PosixFfi
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Userspace consumer for the kernel BPF ring buffer (kernel ≥5.8 layout):
 * one meta page holding `prod_pos`/`consumer_pos`, followed by the data area.
 * Single consumer thread; records may wrap the data ring, so payloads are
 * copied in up to two segments before parsing. Dropped/reserved-by-producer
 * records are simply not yet visible here — producer-side drop counters are
 * authoritative for loss accounting (WP-06).
 *
 * Mapping contract (empirically established on kernel 7.1.4, see
 * backlog/testing/issue-20260825-191000): the meta page is the ONLY writable
 * mapping; the data area must be mapped READ-ONLY through the page-offset
 * alias.
 */
public class RingbufReader(
    ringFd: Int,
    private val dataLength: Long, // must equal round_page(max_entries)
    private val posix: PosixFfi = PosixFfi(),
    private val onEvent: (ContextEvent) -> Unit,
) : AutoCloseable {

    // Meta-page positions are 8-byte aligned within the mapping, so
    // native-endian long reads are safe on our supported arches.
    private val LONG = ValueLayout.JAVA_LONG

    private val metaRw: MemorySegment = posix.mmapShared(PAGE, ringFd)
    private val dataRo: MemorySegment =
        posix.mmapShared(dataLength, ringFd, PosixFfi.PROT_READ, offset = PAGE)
    private val mask: Long = dataLength - 1
    private var closed = false
    private val dbg = System.getenv("TIER_E_RB_DEBUG") != null
    private var dbgEmptyPolls = 0L

    /** Drains whatever is visible; returns number of attributed events handled. */
    public fun pollOnce(): Int {
        if (closed) return 0
        var handled = 0
        var consPos: Long = metaRw.get(LONG, CONS_POS_OFF)
        val prodPos: Long = metaRw.get(LONG, PROD_POS_OFF)
        if (dbg && consPos >= prodPos) {
            dbgEmptyPolls++
            if (dbgEmptyPolls % 100 == 1L) {
                val hdr = if (prodPos > consPos) dataRo.get(LONG, consPos and mask) else -1L
                System.err.println(
                    "[rbdbg] prod=$prodPos cons=$consPos hdr@$consPos=$hdr",
                )
            }
        }
        while (consPos < prodPos) {
            val recOff = consPos and mask
            val header = dataRo.get(LONG, recOff)
            if ((header and BUSY_BIT) != 0L) break // producer still writing

            val payloadLen = (header and LEN_MASK).toInt()
            val discarded = (header and DISCARD_BIT) != 0L
            if (payloadLen < 0 || payloadLen > (dataLength - HEADER_SIZE).toInt()) {
                break // corrupt; refuse to advance blindly
            }

            if (!discarded && payloadLen >= ContextEvent.SIZE_BYTES) {
                val event = readEventAt(recOff + HEADER_SIZE)
                onEvent(event)
                handled++
            }
            consPos += HEADER_SIZE + align8(payloadLen)
            metaRw.set(LONG, CONS_POS_OFF, consPos)
        }
        return handled
    }

    /** Wrap-safe payload read into a heap copy, then typed parse. */
    private fun readEventAt(payloadOffset: Long): ContextEvent {
        val bytes = ByteArray(ContextEvent.SIZE_BYTES)
        val first = minOf(bytes.size.toLong(), dataLength - payloadOffset).toInt()
        MemorySegment.copy(
            dataRo, payloadOffset,
            MemorySegment.ofArray(bytes), 0L, first.toLong(),
        )
        if (first < bytes.size) {
            MemorySegment.copy(
                dataRo, 0L,
                MemorySegment.ofArray(bytes), first.toLong(), (bytes.size - first).toLong(),
            )
        }
        return ContextEvent.fromBytes(bytes)
    }

    override fun close() {
        if (!closed) {
            posix.munmap(dataRo, dataLength)
            posix.munmap(metaRw, PAGE)
            closed = true
        }
    }

    private companion object {
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
