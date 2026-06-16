package io.mazewall.core

/**
 * Type-safe wrapper for file descriptors to prevent transposition bugs.
 */
public class FileDescriptor(
    public val fd: Int,
    public val arena: java.lang.foreign.Arena? = null
) : AutoCloseable {
    private var closed = false

    public val isValid: Boolean get() = !closed && fd >= 0 && (arena == null || arena.scope().isAlive)
    public val isInvalid: Boolean get() = !isValid

    override fun close() {
        if (closed || fd < 0) return
        closed = true
    }

    override fun toString(): String = if (isValid) "fd($fd)" else "fd($fd, closed/invalid)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileDescriptor) return false
        return fd == other.fd
    }

    override fun hashCode(): Int = fd
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
