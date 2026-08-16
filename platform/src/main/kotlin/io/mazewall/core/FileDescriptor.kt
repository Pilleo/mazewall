package io.mazewall.core


import io.mazewall.LinuxNative
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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
 * Process-wide generation stamps so a leftover [FdState.Open] token cannot
 * operate on a later kernel reuse of the same integer.
 */
internal object FdEpoch {
    private data class Slot(val generation: Long, val live: Boolean)

    private val table = ConcurrentHashMap<Int, AtomicReference<Slot>>()

    fun claimOpen(fd: Int): Long {
        if (fd < 0) return 0L
        val ref = table.computeIfAbsent(fd) { AtomicReference(Slot(0L, false)) }
        while (true) {
            val cur = ref.get()
            if (cur.live) {
                return cur.generation
            }
            val next = Slot(cur.generation + 1L, true)
            if (ref.compareAndSet(cur, next)) {
                return next.generation
            }
        }
    }

    fun retire(fd: Int, generation: Long) {
        if (fd < 0) return
        val ref = table[fd] ?: return
        while (true) {
            val cur = ref.get()
            if (!cur.live || cur.generation != generation) {
                return
            }
            if (ref.compareAndSet(cur, Slot(generation, false))) {
                return
            }
        }
    }

    fun isLive(fd: Int, generation: Long): Boolean {
        if (fd < 0) return false
        val cur = table[fd]?.get() ?: return false
        return cur.live && cur.generation == generation
    }
}

/**
 * Shared close flag and generation for every typed view of the same kernel descriptor.
 *
 * Kotlin cannot consume the original [FdState.Open] token when [close] returns a
 * [FdState.Closed] view, so both views must observe the same lifecycle bit.
 */
internal class FdLifecycle(
    val value: Int,
    val arena: NativeArena?,
    val generation: Long,
    val role: FileDescriptorRole,
    @Volatile var closed: Boolean,
)

/**
 * A type-safe wrapper for a Linux file descriptor.
 *
 * This class uses phantom types to distinguish between different roles of file descriptors
 * (e.g., a Landlock ruleset vs a directory opened with O_PATH), preventing transposition
 * bugs where an incorrect FD type is passed to a system call.
 *
 * ### Immutability & Lifecycle
 * The integer identity is immutable. [close] transitions the *type* from [FdState.Open]
 * to [FdState.Closed] by returning a new view, so I/O APIs that require [FdState.Open]
 * reject the closed token at compile time. The original Open-typed variable still exists
 * (Kotlin has no linear types) but shares this handle's closed flag and [FdEpoch]
 * generation, so leftover tokens cannot pass [isLiveForIo] after close or after Linux
 * reuses the integer.
 *
 * @param R The role of this file descriptor (e.g., [FileDescriptorRole.UnixSocket], [FileDescriptorRole.Ruleset]).
 * @param S The state of this file descriptor (e.g., [FdState.Open], [FdState.Closed]).
 * @property value The raw integer file descriptor.
 * @property arena An optional [NativeArena] that owns the native memory lifetime of this descriptor.
 */
public class FileDescriptor<out R : FileDescriptorRole, out S : FdState> internal constructor(
    private val lifecycle: FdLifecycle,
) {
    public val value: Int get() = lifecycle.value
    public val arena: NativeArena? get() = lifecycle.arena
    internal val generation: Long get() = lifecycle.generation
    internal val role: FileDescriptorRole get() = lifecycle.role

    /** Returns true if the file descriptor is open and valid. */
    public val isValid: Boolean
        get() {
            val owner = lifecycle.arena
            return value >= 0 &&
                (owner == null || owner.isAlive) &&
                !lifecycle.closed &&
                FdEpoch.isLive(value, lifecycle.generation)
        }

    /** Returns true if the file descriptor is closed or invalid. */
    public val isInvalid: Boolean get() = !isValid

    /**
     * Runtime gate for NativeEngine I/O. False for leftover Open tokens after
     * [close] and for tokens whose generation does not match the live epoch.
     */
    public fun isLiveForIo(): Boolean = isValid

    internal fun isClosedType(): Boolean {
        return lifecycle.closed || value < 0
    }

    internal fun markClosed() {
        lifecycle.closed = true
        FdEpoch.retire(value, lifecycle.generation)
    }

    /**
     * Retires this generation before [close](2) so leftover Open tokens cannot
     * race onto a reused integer. Idempotent when the epoch already moved on.
     */
    public fun retireForClose() {
        FdEpoch.retire(value, lifecycle.generation)
    }

    override fun toString(): String = if (isValid) "fd($value)" else "fd($value, closed/invalid)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileDescriptor<*, *>) return false
        return value == other.value && generation == other.generation
    }

    override fun hashCode(): Int = 31 * value + generation.hashCode()

    public companion object {
        /**
         * Represents an invalid or uninitialized file descriptor.
         * Uses Nothing role to be compatible with all specific FD roles.
         */
        @Suppress("UNCHECKED_CAST")
        public val INVALID: FileDescriptor<Nothing, FdState.Closed> =
            FileDescriptor<FileDescriptorRole.Generic, FdState.Closed>(
                FdLifecycle(-1, null, generation = 0L, role = FileDescriptorRole.Generic, closed = true),
            ) as FileDescriptor<Nothing, FdState.Closed>

        /**
         * Unsafely creates a [FileDescriptor] from a raw integer.
         * Prefer the role-specific factories ([generic], [unixSocket], [ruleset], [oPath], [seccompNotif]).
         *
         * @param value The raw Linux file descriptor integer.
         * @param arena Optional arena bound to this descriptor's native lifetime.
         * @return A type-safe [FileDescriptor] in the [FdState.Open] state.
         */
        @Suppress("UNCHECKED_CAST")
        public fun <R : FileDescriptorRole> unsafe(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open> =
            open<FileDescriptorRole.Generic>(value, arena, FileDescriptorRole.Generic) as FileDescriptor<R, FdState.Open>

        public fun generic(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.Generic, FdState.Open> =
            open(value, arena, FileDescriptorRole.Generic)

        public fun unixSocket(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> =
            open(value, arena, FileDescriptorRole.UnixSocket)

        public fun ruleset(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open> =
            open(value, arena, FileDescriptorRole.Ruleset)

        public fun oPath(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.OPath, FdState.Open> =
            open(value, arena, FileDescriptorRole.OPath)

        public fun seccompNotif(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open> =
            open(value, arena, FileDescriptorRole.SeccompNotif)

        @Suppress("UNCHECKED_CAST")
        private fun <R : FileDescriptorRole> open(
            value: Int,
            arena: NativeArena?,
            role: FileDescriptorRole,
        ): FileDescriptor<R, FdState.Open> {
            val closed = value < 0
            val generation = if (closed) 0L else FdEpoch.claimOpen(value)
            return FileDescriptor(
                FdLifecycle(value, arena, generation, role, closed = closed),
            )
        }

        internal fun <R : FileDescriptorRole> closedView(
            source: FileDescriptor<R, *>,
        ): FileDescriptor<R, FdState.Closed> {
            source.markClosed()
            return FileDescriptor(source.lifecycle)
        }
    }
}

/**
 * Fail-closed result for NativeEngine I/O when this token is not the live generation.
 */
public fun FileDescriptor<*, *>.ebadfUnlessLive(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    if (isLiveForIo()) {
        return null
    }
    return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1L)
}

public fun NativeArg.ebadfUnlessLive(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    return if (this is NativeArg.FdArg) fd.ebadfUnlessLive() else null
}

public fun ebadfUnlessLive(vararg args: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    for (arg in args) {
        val denied = arg.ebadfUnlessLive()
        if (denied != null) {
            return denied
        }
    }
    return null
}

/**
 * Closes the descriptor via [LinuxNative.fileSystem].
 *
 * This method is restricted to [FdState.Open] file descriptors and returns a new
 * [FileDescriptor] of state [FdState.Closed] to provide compile-time safety against
 * use-after-close errors.
 *
 * @return A new [FileDescriptor] instance with the same value but [FdState.Closed] state.
 */
public fun <R : FileDescriptorRole, S : FdState.Open> FileDescriptor<R, S>.close(): FileDescriptor<R, FdState.Closed> {
    if (value >= 0 && !isClosedType()) {
        @Suppress("UNCHECKED_CAST")
        LinuxNative.fileSystem.close(this as FileDescriptor<*, FdState.Open>)
        arena?.close()
    }
    return FileDescriptor.closedView(this)
}

/**
 * Executes the given [block] with this file descriptor and then closes it correctly,
 * even if an exception is thrown.
 */
public inline fun <R : FileDescriptorRole, S : FdState.Open, T> FileDescriptor<R, S>.use(block: (FileDescriptor<R, S>) -> T): T {
    try {
        return block(this)
    } finally {
        this.close()
    }
}
