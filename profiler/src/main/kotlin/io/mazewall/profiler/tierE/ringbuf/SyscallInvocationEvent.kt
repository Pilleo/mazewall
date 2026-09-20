package io.mazewall.profiler.tierE.ringbuf

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

/**
 * Ringbuf record from the invocation-aware Tier E program. Not Bill-of-Behavior evidence.
 * Join to a Java stack by [invocationId] only; timestamp correlation is invalid.
 */
internal class SyscallInvocationEvent private constructor(
    public val ktimeNs: ULong,
    public val tgid: UInt,
    public val tid: UInt,
    public val syscallNr: Int,
    public val flags: Int,
    public val invocationId: ULong,
    public val sequence: ULong,
    private val ownedArgs: LongArray,
    private val eagerArgumentView: Boolean,
) {
    public constructor(
        ktimeNs: ULong,
        tgid: UInt,
        tid: UInt,
        syscallNr: Int,
        flags: Int,
        invocationId: ULong,
        sequence: ULong,
        args: List<ULong>,
    ) : this(ktimeNs, tgid, tid, syscallNr, flags, invocationId, sequence, copyArguments(args), false)

    /**
     * Compatibility view over event-owned primitive arguments.
     *
     * The collector transfers [ownedArgs] directly into its immutable attribution record, so
     * eagerly boxing a list here would be an unused allocation on every production event.
     */
    private var argsView: List<ULong>? = null
    public val args: List<ULong>
        get() = argsView ?: OwnedULongArguments(ownedArgs).also { argsView = it }
    private var argumentsTransferred: Boolean = false

    init {
        require(ownedArgs.size == ARGUMENT_COUNT) { "syscall event needs exactly $ARGUMENT_COUNT arguments" }
        if (eagerArgumentView) argsView = OwnedULongArguments(ownedArgs)
    }

    /**
     * Transfers the owned primitive storage to the one attribution built from this event.
     * The read-only [args] compatibility view remains valid because attribution state never
     * mutates the transferred array.
     */
    internal fun takeOwnedArguments(): LongArray {
        check(!argumentsTransferred) { "syscall event arguments were already transferred" }
        argumentsTransferred = true
        return ownedArgs
    }

    override fun equals(other: Any?): Boolean =
        other is SyscallInvocationEvent &&
            ktimeNs == other.ktimeNs &&
            tgid == other.tgid &&
            tid == other.tid &&
            syscallNr == other.syscallNr &&
            flags == other.flags &&
            invocationId == other.invocationId &&
            sequence == other.sequence &&
        args == other.args

    override fun hashCode(): Int = listOf(ktimeNs, tgid, tid, syscallNr, flags, invocationId, sequence, args).hashCode()

    public companion object {
        public const val ARGUMENT_COUNT: Int = 6
        public const val FLAG_VTHREAD_EXPERIMENTAL: Int = 1
        public const val FLAG_ARGUMENT_READ_FAILED: Int = 1 shl 1

        /** Benchmark-only diagnostic: a target syscall had no task-storage attribution state. */
        public const val FLAG_TASK_STORAGE_UNAVAILABLE: Int = 1 shl 2
        public const val SIZE_BYTES: Int = 88

        private const val OFF_KTIME: Int = 0
        private const val OFF_TGID: Int = 8
        private const val OFF_TID: Int = 12
        private const val OFF_SYSCALL_NR: Int = 16
        private const val OFF_FLAGS: Int = 20
        private const val OFF_INVOCATION_ID: Int = 24
        private const val OFF_SEQUENCE: Int = 32
        private const val OFF_ARGS: Int = 40
        private val INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
        private val LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

        public fun fromBytes(
            bytes: ByteArray,
            eagerArgumentView: Boolean = false,
        ): SyscallInvocationEvent {
            require(bytes.size >= SIZE_BYTES) { "syscall invocation event needs $SIZE_BYTES bytes, got ${bytes.size}" }
            return fromOwnedArguments(
                ktimeNs = u64(bytes, OFF_KTIME),
                tgid = u32(bytes, OFF_TGID),
                tid = u32(bytes, OFF_TID),
                syscallNr = u32(bytes, OFF_SYSCALL_NR).toInt(),
                flags = u32(bytes, OFF_FLAGS).toInt(),
                invocationId = u64(bytes, OFF_INVOCATION_ID),
                sequence = u64(bytes, OFF_SEQUENCE),
                ownedArgs = LongArray(ARGUMENT_COUNT) { index -> u64(bytes, OFF_ARGS + index * Long.SIZE_BYTES).toLong() },
                eagerArgumentView = eagerArgumentView,
            )
        }

        /** Copies an event from a callback-scoped ring-buffer slice into immutable JVM state. */
        internal fun fromSegment(
            segment: MemorySegment,
            legacyArgumentListConstruction: Boolean = false,
            eagerArgumentView: Boolean = false,
        ): SyscallInvocationEvent {
            require(segment.byteSize() >= SIZE_BYTES) {
                "syscall invocation event needs $SIZE_BYTES bytes, got ${segment.byteSize()}"
            }
            val ktimeNs = u64(segment, OFF_KTIME)
            val tgid = u32(segment, OFF_TGID)
            val tid = u32(segment, OFF_TID)
            val syscallNr = u32(segment, OFF_SYSCALL_NR).toInt()
            val flags = u32(segment, OFF_FLAGS).toInt()
            val invocationId = u64(segment, OFF_INVOCATION_ID)
            val sequence = u64(segment, OFF_SEQUENCE)
            val ownedArgs = LongArray(ARGUMENT_COUNT) { index -> u64(segment, OFF_ARGS + index * Long.SIZE_BYTES).toLong() }
            if (legacyArgumentListConstruction) {
                return SyscallInvocationEvent(
                    ktimeNs = ktimeNs,
                    tgid = tgid,
                    tid = tid,
                    syscallNr = syscallNr,
                    flags = flags,
                    invocationId = invocationId,
                    sequence = sequence,
                    args = List(ARGUMENT_COUNT) { index -> ownedArgs[index].toULong() },
                )
            }
            return fromOwnedArguments(
                ktimeNs = ktimeNs,
                tgid = tgid,
                tid = tid,
                syscallNr = syscallNr,
                flags = flags,
                invocationId = invocationId,
                sequence = sequence,
                ownedArgs = ownedArgs,
                eagerArgumentView = eagerArgumentView,
            )
        }

        internal fun fromOwnedArguments(
            ktimeNs: ULong,
            tgid: UInt,
            tid: UInt,
            syscallNr: Int,
            flags: Int,
            invocationId: ULong,
            sequence: ULong,
            ownedArgs: LongArray,
            eagerArgumentView: Boolean = false,
        ): SyscallInvocationEvent = SyscallInvocationEvent(ktimeNs, tgid, tid, syscallNr, flags, invocationId, sequence, ownedArgs, eagerArgumentView)

        private fun copyArguments(args: List<ULong>): LongArray {
            require(args.size == ARGUMENT_COUNT) { "syscall event needs exactly $ARGUMENT_COUNT arguments" }
            return LongArray(ARGUMENT_COUNT) { index -> args[index].toLong() }
        }

        private fun u32(
            bytes: ByteArray,
            offset: Int,
        ): UInt {
            var value = 0u
            repeat(Int.SIZE_BYTES) { index -> value = value or ((bytes[offset + index].toUInt() and 0xFFu) shl (index * 8)) }
            return value
        }

        private fun u64(
            bytes: ByteArray,
            offset: Int,
        ): ULong {
            var value = 0uL
            repeat(Long.SIZE_BYTES) { index -> value = value or ((bytes[offset + index].toULong() and 0xFFuL) shl (index * 8)) }
            return value
        }

        private fun u32(
            segment: MemorySegment,
            offset: Int,
        ): UInt = segment.get(INT_LE, offset.toLong()).toUInt()

        private fun u64(
            segment: MemorySegment,
            offset: Int,
        ): ULong = segment.get(LONG_LE, offset.toLong()).toULong()
    }
}

/** Read-only boxed view over the primitive arguments owned by one ring event. */
private class OwnedULongArguments(
    private val values: LongArray,
) : java.util.AbstractList<ULong>() {
    override val size: Int
        get() = values.size

    override fun get(index: Int): ULong = values[index].toULong()
}
