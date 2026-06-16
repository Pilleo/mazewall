package io.mazewall.core

import io.mazewall.LinuxNative
import java.lang.foreign.Arena

/**
 * Marker interfaces for File Descriptor roles to provide compile-time safety.
 */
public sealed interface FileDescriptorRole {
    public data object Generic : FileDescriptorRole
    public data object Ruleset : FileDescriptorRole
    public data object OPath : FileDescriptorRole
    public data object SeccompNotif : FileDescriptorRole
    public data object UnixSocket : FileDescriptorRole
}

/**
 * A type-safe wrapper for a Linux file descriptor.
 *
 * This class uses phantom types to distinguish between different roles of file descriptors
 * (e.g., a Landlock ruleset vs a directory opened with O_PATH), preventing transposition
 * bugs where an incorrect FD type is passed to a system call.
 *
 * It also provides deterministic resource management via [AutoCloseable], automatically
 * invoking the kernel's `close` system call through the active [LinuxNative] engine.
 *
 * @param R The role of this file descriptor.
 * @property value The raw integer file descriptor.
 * @property arena An optional [Arena] that owns the lifetime of this file descriptor.
 */
public class FileDescriptor<out R : FileDescriptorRole>(
    public val value: Int,
    public val arena: Arena? = null
) : AutoCloseable {
    private var closed = false

    /** Returns true if the file descriptor is open and valid. */
    public val isValid: Boolean get() = !closed && value >= 0 && (arena == null || arena.scope().isAlive)

    /** Returns true if the file descriptor is closed or invalid. */
    public val isInvalid: Boolean get() = !isValid

    override fun close() {
        if (closed || value < 0) return
        closed = true
        // Delegate actual kernel close to the native engine
        LinuxNative.fileSystem.close(this)
        arena?.close()
    }

    override fun toString(): String = if (isValid) "fd($value)" else "fd($value, closed/invalid)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileDescriptor<*>) return false
        return value == other.value
    }

    override fun hashCode(): Int = value

    public companion object {
        /**
         * Represents an invalid or uninitialized file descriptor.
         * Uses Nothing role to be compatible with all specific FD roles.
         */
        @Suppress("UNCHECKED_CAST")
        public val INVALID: FileDescriptor<Nothing> = FileDescriptor<FileDescriptorRole.Generic>(-1) as FileDescriptor<Nothing>

        /**
         * Unsafely creates a [FileDescriptor] from a raw integer.
         * Prefer using domain-specific factory methods or extensions.
         */
        @Suppress("UNCHECKED_CAST")
        public fun <R : FileDescriptorRole> unsafe(value: Int): FileDescriptor<R> =
            FileDescriptor<FileDescriptorRole>(value) as FileDescriptor<R>
    }
}
