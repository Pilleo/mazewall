package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.NativeArena

/**
 * Marker interfaces for File Descriptor lifecycle states.
 */
public sealed interface FdState {
    /** The descriptor is open and valid for I/O operations. */
    public interface Open : FdState

    /** The descriptor has been closed and is no longer valid. */
    public interface Closed : FdState
}

/**
 * Marker interfaces for File Descriptor roles to provide compile-time safety.
 */
public sealed interface FileDescriptorRole {
    /** A generic file descriptor with no specialized role. */
    public data object Generic : FileDescriptorRole

    /** A Landlock ruleset file descriptor. */
    public data object Ruleset : FileDescriptorRole

    /** A directory or file descriptor opened with O_PATH. */
    public data object OPath : FileDescriptorRole

    /** A seccomp user notification listener file descriptor. */
    public data object SeccompNotif : FileDescriptorRole

    /** A Unix domain socket file descriptor. */
    public data object UnixSocket : FileDescriptorRole
}

/**
 * A type-safe wrapper for a Linux file descriptor.
 *
 * This class uses phantom types to distinguish between different roles of file descriptors
 * (e.g., a Landlock ruleset vs a directory opened with O_PATH), preventing transposition
 * bugs where an incorrect FD type is passed to a system call.
 *
 * ### Immutability & Lifecycle
 * This class is **strictly immutable**. To ensure compile-time safety against Use-After-Close
 * errors, the [closeFd] method transitions the type from [FdState.Open] to [FdState.Closed] by
 * returning a new instance.
 *
 * @param R The role of this file descriptor (e.g., [FileDescriptorRole.UnixSocket], [FileDescriptorRole.Ruleset]).
 * @param S The state of this file descriptor (e.g., [FdState.Open], [FdState.Closed]).
 * @property value The raw integer file descriptor.
 * @property arena An optional [NativeArena] that owns the native memory lifetime of this descriptor.
 */
public class FileDescriptor<out R : FileDescriptorRole, out S : FdState>(
    public val value: Int,
    public val arena: NativeArena? = null
) : AutoCloseable {

    /** Returns true if the file descriptor is open and valid. */
    public val isValid: Boolean get() = value >= 0 && (arena == null || arena.isAlive)

    /** Returns true if the file descriptor is closed or invalid. */
    public val isInvalid: Boolean get() = !isValid

    /**
     * Closes the descriptor via [LinuxNative.fileSystem].
     *
     * Note: This method satisfies [AutoCloseable] for convenience in try-with-resources,
     * but since [FileDescriptor] is immutable, the instance itself remains of type [FdState.Open].
     * To obtain a [FdState.Closed] type that is enforced by the compiler, use [closeFd].
     */
    override fun close() {
        if (value < 0) return
        if (this.isClosedType()) return

        @Suppress("UNCHECKED_CAST")
        LinuxNative.fileSystem.close(this as FileDescriptor<*, FdState.Open>)
        arena?.close()
    }

    /**
     * Closes the descriptor and returns it cast to a [FdState.Closed] state at compile-time.
     *
     * This is the preferred way to close descriptors when you want the type system
     * to prevent any further usage of the closed resource.
     *
     * @return A new [FileDescriptor] instance with the same value but [FdState.Closed] state.
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
         *
         * @param value The raw Linux file descriptor integer.
         * @return A type-safe [FileDescriptor] in the [FdState.Open] state.
         */
        @Suppress("UNCHECKED_CAST")
        public fun <R : FileDescriptorRole> unsafe(value: Int): FileDescriptor<R, FdState.Open> =
            FileDescriptor<FileDescriptorRole, FdState.Open>(value) as FileDescriptor<R, FdState.Open>
    }
}
