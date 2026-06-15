package io.mazewall.core

/**
 * Type-safe wrapper for file descriptors to prevent transposition bugs.
 */
@JvmInline
value class FileDescriptor(
    val fd: Int,
) {
    override fun toString(): String = "fd($fd)"
}

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
