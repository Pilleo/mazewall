package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.ManagedSegment

/**
 * A sealed interface representing valid argument types that can be passed to native system calls.
 * This ensures that parameters are validated at compile-time, replacing the error-prone 'Any?' type.
 */
public sealed interface NativeArg {
    /**
     * Converts this argument to a native [Long] representation suitable for FFM downcalls.
     */
    public val asLong: Long

    @JvmInline
    public value class LongArg(public val value: Long) : NativeArg {
        override val asLong: Long get() = value
    }

    @JvmInline
    public value class IntArg(public val value: Int) : NativeArg {
        override val asLong: Long get() = value.toLong()
    }

    @JvmInline
    public value class MemoryArg(public val value: ManagedSegment) : NativeArg {
        override val asLong: Long get() = value.address()
    }

    @JvmInline
    public value class FdArg(public val fd: FileDescriptor<*, *>) : NativeArg {
        override val asLong: Long get() = fd.value.toLong()
    }

    @JvmInline
    public value class PidArg(public val pid: Pid) : NativeArg {
        override val asLong: Long get() = pid.value.toLong()
    }

    @JvmInline
    public value class UidArg(public val uid: Uid) : NativeArg {
        override val asLong: Long get() = uid.value.toLong()
    }

    @JvmInline
    public value class AddrArg(public val addr: MemoryAddress) : NativeArg {
        override val asLong: Long get() = addr.value
    }

    public data object NullArg : NativeArg {
        override val asLong: Long get() = 0L
    }
}
