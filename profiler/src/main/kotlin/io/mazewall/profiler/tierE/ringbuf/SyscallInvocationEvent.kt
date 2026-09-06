package io.mazewall.profiler.tierE.ringbuf

/**
 * Fixed little-endian syscall record emitted by the invocation-aware Tier E program.
 *
 * It deliberately carries the explicit native-agent invocation id. Resolution to a Java stack
 * happens later through the matching invocation definition; no timestamp correlation is valid.
 */
public data class SyscallInvocationEvent(
    public val ktimeNs: ULong,
    public val tgid: UInt,
    public val tid: UInt,
    public val syscallNr: Int,
    public val flags: Int,
    public val invocationId: ULong,
    public val sequence: ULong,
    public val args: List<ULong>,
) {
    init {
        require(args.size == ARGUMENT_COUNT) { "syscall event needs exactly $ARGUMENT_COUNT arguments" }
    }

    public companion object {
        public const val ARGUMENT_COUNT: Int = 6
        public const val FLAG_VTHREAD_EXPERIMENTAL: Int = 1
        public const val FLAG_ARGUMENT_READ_FAILED: Int = 1 shl 1
        public const val SIZE_BYTES: Int = 88

        private const val OFF_KTIME: Int = 0
        private const val OFF_TGID: Int = 8
        private const val OFF_TID: Int = 12
        private const val OFF_SYSCALL_NR: Int = 16
        private const val OFF_FLAGS: Int = 20
        private const val OFF_INVOCATION_ID: Int = 24
        private const val OFF_SEQUENCE: Int = 32
        private const val OFF_ARGS: Int = 40

        public fun fromBytes(bytes: ByteArray): SyscallInvocationEvent {
            require(bytes.size >= SIZE_BYTES) { "syscall invocation event needs $SIZE_BYTES bytes, got ${bytes.size}" }
            return SyscallInvocationEvent(
                ktimeNs = u64(bytes, OFF_KTIME),
                tgid = u32(bytes, OFF_TGID),
                tid = u32(bytes, OFF_TID),
                syscallNr = u32(bytes, OFF_SYSCALL_NR).toInt(),
                flags = u32(bytes, OFF_FLAGS).toInt(),
                invocationId = u64(bytes, OFF_INVOCATION_ID),
                sequence = u64(bytes, OFF_SEQUENCE),
                args = List(ARGUMENT_COUNT) { index -> u64(bytes, OFF_ARGS + index * Long.SIZE_BYTES) },
            )
        }

        private fun u32(bytes: ByteArray, offset: Int): UInt {
            var value = 0u
            repeat(Int.SIZE_BYTES) { index -> value = value or ((bytes[offset + index].toUInt() and 0xFFu) shl (index * 8)) }
            return value
        }

        private fun u64(bytes: ByteArray, offset: Int): ULong {
            var value = 0uL
            repeat(Long.SIZE_BYTES) { index -> value = value or ((bytes[offset + index].toULong() and 0xFFuL) shl (index * 8)) }
            return value
        }
    }
}
