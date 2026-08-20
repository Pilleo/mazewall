package io.mazewall.core


import io.mazewall.LinuxNative
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
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

    /** A pidfd from pidfd_open(2). */
    public data object Pid : FileDescriptorRole
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

    /** True if this integer was claimed and later retired (not an untracked fd). */
    fun isRetired(fd: Int): Boolean {
        if (fd < 0) return false
        val cur = table[fd]?.get() ?: return false
        return !cur.live && cur.generation > 0L
    }

    /**
     * Marks the integer not live so the next [claimOpen] is a new generation.
     * Used for dup2-style replacement and SECCOMP_ADDFD SETFD.
     */
    fun forceRetire(fd: Int) {
        if (fd < 0) return
        val ref = table[fd] ?: return
        while (true) {
            val cur = ref.get()
            if (!cur.live) {
                return
            }
            if (ref.compareAndSet(cur, Slot(cur.generation, false))) {
                return
            }
        }
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

    /** [AT_FDCWD] or a live directory descriptor. */
    public fun isUsableAsDirfd(): Boolean =
        value == NativeConstants.AT_FDCWD || isLiveForIo()

    /** Negative fd (anonymous mmap) or a live backing file. */
    public fun isUsableAsMmapBacking(): Boolean = value < 0 || isLiveForIo()

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
         * Sentinel for openat(2) [NativeConstants.AT_FDCWD]. Not a live kernel fd.
         */
        public val AT_FDCWD: FileDescriptor<FileDescriptorRole.OPath, FdState.Open> =
            FileDescriptor(
                FdLifecycle(NativeConstants.AT_FDCWD, null, generation = 0L, role = FileDescriptorRole.OPath, closed = false),
            )

        /**
         * Sentinel for anonymous mmap(2) (`fd = -1`). Not a live kernel fd.
         */
        public val ANON: FileDescriptor<FileDescriptorRole.Generic, FdState.Open> =
            FileDescriptor(
                FdLifecycle(-1, null, generation = 0L, role = FileDescriptorRole.Generic, closed = true),
            )

        /**
         * Unsafely creates a [FileDescriptor] from a raw integer.
         * Does NOT claim the FD in the epoch - for retired FDs, creates a non-live token.
         * Prefer the role-specific factories ([generic], [unixSocket], [ruleset], [oPath], [seccompNotif]).
         *
         * WARNING: This method will NOT revive retired file descriptors. For kernel-reused
         * integers from dup/accept/SCM_RIGHTS, use [adopt] or [replace] instead.
         *
         * @param value The raw Linux file descriptor integer.
         * @param arena Optional arena bound to this descriptor's native lifetime.
         * @return A type-safe [FileDescriptor] in the [FdState.Open] state.
         */
        @Deprecated(
            message = "Use role-specific factories (generic, unixSocket, ruleset, oPath, seccompNotif, pid) or adopt() for kernel-reused FDs. " +
                      "This method creates non-live tokens for retired FDs and should not be used in production code.",
            level = DeprecationLevel.WARNING
        )
        @Suppress("UNCHECKED_CAST")
        public fun <R : FileDescriptorRole> unsafe(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open> {
            if (value >= 0 && FdEpoch.isRetired(value)) {
                // Retired FD: mint a non-live token (generation 0, closed)
                // This prevents leftover Open tokens from operating on reused integers
                return FileDescriptor<FileDescriptorRole.Generic, FdState.Open>(
                    FdLifecycle(value, arena, 0L, FileDescriptorRole.Generic, closed = true),
                ) as FileDescriptor<R, FdState.Open>
            }
            // Non-retired or negative: delegate to open which will claim a new generation
            return open<FileDescriptorRole.Generic>(value, arena, FileDescriptorRole.Generic) as FileDescriptor<R, FdState.Open>
        }

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

        public fun pid(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.Pid, FdState.Open> =
            open(value, arena, FileDescriptorRole.Pid)

        /**
         * Adopts a newly allocated kernel fd (open, accept, dup, SCM_RIGHTS).
         * Always creates a new generation, even if [value] was previously retired.
         * Leftover Open tokens from the old generation stay dead.
         */
        @Suppress("UNCHECKED_CAST")
        public fun <R : FileDescriptorRole> adopt(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open> =
            open<FileDescriptorRole.Generic>(value, arena, FileDescriptorRole.Generic) as FileDescriptor<R, FdState.Open>

        /**
         * Kernel replaced this integer (dup2, SECCOMP_ADDFD SETFD).
         * Any leftover generation is retired first, then a new generation is claimed.
         */
        @Suppress("UNCHECKED_CAST")
        public fun <R : FileDescriptorRole> replace(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open> {
            if (value >= 0) {
                FdEpoch.forceRetire(value)
            }
            // Claim a new generation for the replaced FD
            return open<FileDescriptorRole.Generic>(value, arena, FileDescriptorRole.Generic) as FileDescriptor<R, FdState.Open>
        }

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

public fun FileDescriptor<*, *>.ebadfUnlessDirfd(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    if (isUsableAsDirfd()) {
        return null
    }
    return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1L)
}

public fun FileDescriptor<*, *>.ebadfUnlessMmapBacking(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    if (isUsableAsMmapBacking()) {
        return null
    }
    return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1L)
}

public fun NativeArg.ebadfUnlessLive(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    return if (this is NativeArg.FdArg) fd.ebadfUnlessLive() else null
}

public fun ebadfIfRetiredPollfds(
    fds: ManagedSegment,
    nfds: Long,
): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    val stride = io.mazewall.ffi.Layouts.POLLFD_SIZE
    var i = 0L
    while (i < nfds) {
        val fd = fds.readInt(i * stride + io.mazewall.ffi.Layouts.POLLFD_FD_OFFSET)
        if (FdEpoch.isRetired(fd)) {
            return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1L)
        }
        i++
    }
    return null
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

public fun LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>.claimDupIfNeeded(
    cmd: Int,
): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
    if (this is LinuxNative.SyscallResult.Success &&
        (cmd == NativeConstants.F_DUPFD || cmd == NativeConstants.F_DUPFD_CLOEXEC) &&
        value >= 0L
    ) {
        FdEpoch.claimOpen(value.toInt())
    }
    return this
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
