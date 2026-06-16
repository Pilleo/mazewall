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
 * Type-safe wrapper for process IDs.
 */
@JvmInline
public value class Pid(val value: Int) {
    override fun toString(): String = "pid($value)"
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
