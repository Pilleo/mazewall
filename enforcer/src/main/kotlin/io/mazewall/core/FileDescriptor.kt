package io.mazewall.core

import io.mazewall.LinuxNative
import java.lang.foreign.Arena

/**
 * Marker interfaces for File Descriptor lifecycle states.
 */
public sealed interface FdState {
    public interface Open : FdState
    public interface Closed : FdState
}

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
 * It is strictly immutable. Lifecycle transitions (Open -> Closed) are managed by
 * the [LinuxNative.fileSystem] engine, which consumes an [Open] descriptor and
 * returns a [Closed] one.
 *
 * @param R The role of this file descriptor.
 * @param S The state of this file descriptor.
 * @property value The raw integer file descriptor.
 * @property arena An optional [Arena] that owns the lifetime of this file descriptor.
 */
public class FileDescriptor<out R : FileDescriptorRole, out S : FdState>(
    public val value: Int,
    public val arena: Arena? = null
) : AutoCloseable {

    /** Returns true if the file descriptor is open and valid. */
    public val isValid: Boolean get() = value >= 0 && (arena == null || arena.scope().isAlive)

    /** Returns true if the file descriptor is closed or invalid. */
    public val isInvalid: Boolean get() = !isValid

    /**
     * Closes the descriptor via [LinuxNative.fileSystem].
     *
     * Note: This method satisfies [AutoCloseable] for convenience in try-with-resources,
     * but since [FileDescriptor] is immutable, the instance itself remains of type [Open].
     * To obtain a [Closed] type, use [closeFd].
     */
    override fun close() {
        if (value < 0) return
        if (this.isClosedType()) return

        @Suppress("UNCHECKED_CAST")
        LinuxNative.fileSystem.close(this as FileDescriptor<*, FdState.Open>)
        arena?.close()
    }

    /**
     * Closes the descriptor and returns it cast to a [Closed] state at compile-time.
     */
    @Suppress("UNCHECKED_CAST")
    public fun closeFd(): FileDescriptor<R, FdState.Closed> {
        close()
        return FileDescriptor<R, FdState.Closed>(value, arena)
    }

    private fun isClosedType(): Boolean {
        // We can't easily check S at runtime due to erasure, but we can check if it's INVALID
        return value < 0
    }

    override fun toString(): String = if (isValid) "fd($value)" else "fd($value, closed/invalid)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileDescriptor<*, *>) return false
        return value == other.value
    }

    override fun hashCode(): Int = value

    public companion object {
        /**
         * Represents an invalid or uninitialized file descriptor.
         * Uses Nothing role to be compatible with all specific FD roles.
         */
        @Suppress("UNCHECKED_CAST")
        public val INVALID: FileDescriptor<Nothing, FdState.Closed> = FileDescriptor<FileDescriptorRole.Generic, FdState.Closed>(-1) as FileDescriptor<Nothing, FdState.Closed>

        /**
         * Unsafely creates a [FileDescriptor] from a raw integer.
         * Prefer using domain-specific factory methods or extensions.
         */
        @Suppress("UNCHECKED_CAST")
        public fun <R : FileDescriptorRole> unsafe(value: Int): FileDescriptor<R, FdState.Open> =
            FileDescriptor<FileDescriptorRole, FdState.Open>(value) as FileDescriptor<R, FdState.Open>
    }
}
