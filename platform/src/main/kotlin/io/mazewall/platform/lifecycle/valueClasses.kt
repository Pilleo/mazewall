package io.mazewall.platform.lifecycle

/**
 * Type-safe wrapper for system call numbers.
 */
@JvmInline
internal value class SyscallNumber(
    internal val nr: Int,
) {
    override fun toString(): String = "nr($nr)"
}

/**
 * Type-safe wrapper for POSIX error numbers.
 */
@JvmInline
internal value class Errno(
    internal val value: Int,
) {
    override fun toString(): String = "errno($value)"
}

@JvmInline
public value class Pid(
    public val value: Int,
)

@JvmInline
public value class Tid(
    public val value: Int,
)

@JvmInline
public value class Uid(
    public val value: Int,
)

/**
 * Type-safe wrapper for native memory addresses.
 */
@JvmInline
public value class MemoryAddress(
    public val value: Long,
) {
    public fun toLong(): Long = value

    override fun toString(): String = "0x${value.toString(16)}"
}

/**
 * Type-safe wrapper for open system call flags (e.g., O_RDONLY, O_CLOEXEC).
 */
@JvmInline
public value class OpenFlags(
    public val value: Int,
) {
    public companion object {
        public val RDONLY: OpenFlags = OpenFlags(0)
    }

    public fun has(mask: Int): Boolean = (value and mask) != 0

    override fun toString(): String = "openFlags($value)"
}

/**
 * `newfd_flags` for SECCOMP_ADDFD. Built from the **tracee syscall** flags, not a
 * hardcoded CLOEXEC. Exec inject is [forExec].
 */
@JvmInline
public value class NewFdFlags(
    public val value: Int,
) {
    public companion object {
        public val NONE: NewFdFlags = NewFdFlags(0)
        public val CLOEXEC: NewFdFlags = NewFdFlags(io.mazewall.ffi.NativeConstants.O_CLOEXEC)

        public fun forOpen(flags: OpenFlags): NewFdFlags = if (flags.has(io.mazewall.ffi.NativeConstants.O_CLOEXEC)) CLOEXEC else NONE

        public fun forAccept(sockFlags: Int): NewFdFlags = if ((sockFlags and io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC) != 0) CLOEXEC else NONE

        public fun forExec(): NewFdFlags = CLOEXEC
    }

    override fun toString(): String = "newfdFlags($value)"
}

/**
 * Type-safe wrapper for mmap memory protection flags (e.g., PROT_READ, PROT_WRITE).
 */
@JvmInline
public value class MmapProt(
    public val value: Int,
) {
    override fun toString(): String = "mmapProt($value)"
}

/**
 * Type-safe wrapper for mmap mapping flags (e.g., MAP_SHARED, MAP_PRIVATE).
 */
@JvmInline
public value class MmapFlags(
    public val value: Int,
) {
    override fun toString(): String = "mmapFlags($value)"
}

/**
 * Type-safe wrapper for clone flags (e.g., CLONE_VM, CLONE_THREAD).
 */
@JvmInline
public value class CloneFlags(
    public val value: Long,
) {
    override fun toString(): String = "cloneFlags($value)"
}

@JvmInline
public value class Deadline(
    public val nanoTime: Long,
) {
    public fun remainingMillis(nowNanoTime: Long = System.nanoTime()): Int = ((nanoTime - nowNanoTime).coerceAtLeast(0L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    public fun isExpired(nowNanoTime: Long = System.nanoTime()): Boolean = nowNanoTime >= nanoTime

    public companion object {
        public fun afterMillis(
            ms: Long,
            nowNanoTime: Long = System.nanoTime(),
        ): Deadline {
            require(ms >= 0L) { "deadline duration must be non-negative" }
            val durationNanos = if (ms > Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else ms * 1_000_000L
            val target = if (Long.MAX_VALUE - nowNanoTime < durationNanos) Long.MAX_VALUE else nowNanoTime + durationNanos
            return Deadline(target)
        }
    }
}
