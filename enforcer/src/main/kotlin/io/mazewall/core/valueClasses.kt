package io.mazewall.core

/**
 * Type-safe wrapper for system call numbers.
 */
@JvmInline
value class SyscallNumber(
    val nr: Int,
) {
    override fun toString(): String = "nr($nr)"
}

/**
 * Type-safe wrapper for POSIX error numbers.
 */
@JvmInline
value class Errno(
    val value: Int,
) {
    override fun toString(): String = "errno($value)"
}

/**
 * Type-safe wrapper for Linux Process IDs (TGID).
 *
 * Use [Pid] for process-wide operations such as Landlock restrictions
 * or when targeting a process virtual memory address space.
 */
@JvmInline
public value class Pid(val value: Int) {
    override fun toString(): String = "pid($value)"
}

/**
 * Type-safe wrapper for Linux Thread IDs (LWP/TID).
 *
 * Use [Tid] for thread-specific operations such as seccomp USER_NOTIF
 * profiling or thread-local registries. While a [Tid] can often be
 * used where a [Pid] is expected in kernel APIs (targeting the shared
 * address space), this type distinguishes the semantic intent.
 */
@JvmInline
public value class Tid(val value: Int) {
    override fun toString(): String = "tid($value)"
}

/**
 * Type-safe wrapper for user IDs.
 */
@JvmInline
public value class Uid(val value: Int) {
    override fun toString(): String = "uid($value)"
}

/**
 * Type-safe wrapper for native memory addresses.
 */
@JvmInline
public value class MemoryAddress(val value: Long) {
    public fun toLong(): Long = value

    @Suppress("MagicNumber")
    override fun toString(): String = "0x${value.toString(16)}"
}
