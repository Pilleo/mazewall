package io.mazewall.profiler.tierE.ringbuf

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * One attributed syscall observation as emitted by `context_probe.bpf.c`:
 *
 * ```c
 * struct context_event {
 *     __u64 ktime_ns;
 *     __u32 tgid;
 *     __u32 tid;
 *     __s32 syscall_nr;
 *     __u32 context_id;   // ContextId wire form (UNTRUSTED metadata)
 * };
 * ```
 *
 * This Kotlin declaration is the SINGLE source of truth for the userspace
 * side. When the BPF struct changes, change [LAYOUT] (and the C struct) in
 * exactly one place each — never hand-parse offsets at call sites.
 */
public data class ContextEvent(
    public val ktimeNs: ULong,
    public val tgid: UInt,
    public val tid: UInt,
    public val syscallNr: Int,
    public val contextId: UInt,
) {
    public companion object {

        /** Field offsets within the 24-byte packed record. */
        private const val OFF_KTIME = 0L
        private const val OFF_TGID = 8L
        private const val OFF_TID = 12L
        private const val OFF_SYSCALL_NR = 16L
        private const val OFF_CONTEXT_ID = 20L

        /** Wire size of the record; must match `sizeof(struct context_event)`. */
        public const val SIZE_BYTES: Int = 24

        private val LONG = ValueLayout.JAVA_LONG
        private val INT = ValueLayout.JAVA_INT

        /**
         * Parses a full little-endian record from [bytes] (which may be larger
         * than [SIZE_BYTES]; only the first [SIZE_BYTES] are read).
         *
         * @throws IllegalArgumentException on short input.
         */
        public fun fromBytes(bytes: ByteArray): ContextEvent {
            require(bytes.size >= SIZE_BYTES) {
                "context_event needs $SIZE_BYTES bytes, got ${bytes.size}"
            }
            fun u64(off: Int): ULong {
                var v = 0UL
                for (i in 7 downTo 0) v = (v shl 8) or (bytes[off + i].toULong() and 0xFFu)
                return v
            }
            fun u32(off: Int): UInt {
                var v = 0u
                for (i in 3 downTo 0) v = (v shl 8) or (bytes[off + i].toUInt() and 0xFFu)
                return v
            }
            return ContextEvent(
                ktimeNs = u64(OFF_KTIME.toInt()),
                tgid = u32(OFF_TGID.toInt()),
                tid = u32(OFF_TID.toInt()),
                syscallNr = u32(OFF_SYSCALL_NR.toInt()).toInt(),
                contextId = u32(OFF_CONTEXT_ID.toInt()),
            )
        }

        /**
         * Native-endian read straight from a mapped ring-buffer segment.
         * All fields are naturally aligned inside the 24-byte record.
         */
        public fun fromSegment(seg: MemorySegment, offset: Long): ContextEvent = ContextEvent(
            ktimeNs = seg.get(LONG, offset + OFF_KTIME).toULong(),
            tgid = seg.get(INT, offset + OFF_TGID).toUInt(),
            tid = seg.get(INT, offset + OFF_TID).toUInt(),
            syscallNr = seg.get(INT, offset + OFF_SYSCALL_NR),
            contextId = seg.get(INT, offset + OFF_CONTEXT_ID).toUInt(),
        )
    }
}
