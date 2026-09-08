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

    /**
     * A descriptor granted by a more-privileged process over `SCM_RIGHTS`
     * (broker → worker). Not a seccomp listener.
     */
    public data object Granted : FileDescriptorRole
}

/**
 * Ownership status of a file descriptor.
 *
 * - **Owned**: The descriptor was created via [adopt], [replace], or [claimDupIfNeeded].
 *   It represents a real file descriptor that this code owns and is responsible for closing.
 * - **Unowned**: The descriptor was created via [unsafe], [generic], or role-specific factories
 *   like [unixSocket], [seccompNotif], etc. It represents an invented integer or a descriptor
 *   owned by another component. Closing an Unowned descriptor will throw [IllegalStateException].
 */
public sealed interface FdOwnership {
    /** This descriptor is owned by the current code and can be closed. */
    public data object Owned : FdOwnership

    /** This descriptor is not owned by the current code and must not be closed. */
    public data object Unowned : FdOwnership
}

/**
 * Process-wide generation stamps so a leftover [FdState.Open] token cannot
 * operate on a later kernel reuse of the same integer.
 */
internal object FdEpoch {
    private data class Slot(
        val generation: Long,
        val live: Boolean,
        val owned: Boolean,
    )

    private val table = ConcurrentHashMap<Int, AtomicReference<Slot>>()

    // Audit ledger: tracks which fds were explicitly marked as owned through this epoch
    private val ownedThroughEpoch = ConcurrentHashMap.newKeySet<Int>()

    /** Returns true if audit mode is enabled via -Dmazewall.fd.audit=true */
    private fun isAuditEnabled(): Boolean = System.getProperty("mazewall.fd.audit")?.lowercase() == "true"

    /**
     * Claims this integer as a still-owned live descriptor.
     * If the slot is already live, returns that generation so aliases of the
     * same resource share a token. Does not bump a leftover live slot.
     */
    fun claimOpen(fd: FileDescriptor<*, *, *>): Long = claimOpen(fd.value)

    fun claimOpen(fd: Int): Long {
        if (fd < 0) return 0L
        val ref = table.computeIfAbsent(fd) { AtomicReference(Slot(0L, false, false)) }
        while (true) {
            val cur = ref.get()
            if (cur.live) {
                return cur.generation
            }
            val next = Slot(cur.generation + 1L, true, false)
            if (ref.compareAndSet(cur, next)) {
                return next.generation
            }
        }
    }

    /**
     * Kernel reused this integer for a newly minted descriptor
     * (open, accept, dup, SCM_RIGHTS). Any leftover live generation is retired
     * so tokens for the old resource cannot operate on the new one.
     */
    fun adoptKernelReuse(fd: Int): Long {
        if (fd < 0) return 0L
        forceRetire(fd)
        return claimOpen(fd)
    }

    fun retire(
        fd: Int,
        generation: Long,
    ) {
        if (fd < 0) return
        val ref = table[fd] ?: return
        while (true) {
            val cur = ref.get()
            if (!cur.live || cur.generation != generation) {
                return
            }
            if (ref.compareAndSet(cur, Slot(generation, false, cur.owned))) {
                return
            }
        }
    }

    fun isLive(
        fd: Int,
        generation: Long,
    ): Boolean {
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
            if (ref.compareAndSet(cur, Slot(cur.generation, false, cur.owned))) {
                return
            }
        }
    }

    /**
     * Marks an fd as owned (opened by this process via adopt).
     */
    fun markOwned(fd: Int) {
        if (fd >= 0) {
            val ref = table[fd] ?: return
            while (true) {
                val cur = ref.get()
                if (cur.owned) return
                if (ref.compareAndSet(cur, Slot(cur.generation, cur.live, true))) {
                    ownedThroughEpoch.add(fd)
                    return
                }
            }
        }
    }

    /**
     * Returns true if this fd was explicitly marked as owned through this epoch.
     */
    fun isOwnedThroughEpoch(fd: Int): Boolean {
        return ownedThroughEpoch.contains(fd)
    }

    /**
     * Verifies via fcntl(F_GETFD) that the target still exists.
     * Returns true if the fd is valid according to the kernel.
     *
     */
    fun verifyKernelLiveness(fd: Int): Boolean = LinuxNative.isDescriptorLive(fd)

    /**
     * Audit ledger check: verifies and logs before close.
     * In audit mode (-Dmazewall.fd.audit=true), warns if closing an fd that
     * was never marked as owned through the epoch.
     *
     * Returns true if the close should proceed.
     */
    fun auditClose(
        fd: Int,
        generation: Long,
    ): Boolean {
        if (!isAuditEnabled()) return true

        val wasOwned = isOwnedThroughEpoch(fd)
        val isLive = isLive(fd, generation)
        val existsInKernel = verifyKernelLiveness(fd)

        if (!existsInKernel) {
            System.err.println(
                "[FdEpoch Audit] WARNING: suppressing close of fd=$fd because fcntl(F_GETFD) reports EBADF. " +
                    "The descriptor was already closed or reused outside this lifecycle.",
            )
            return false
        }

        if (!wasOwned && isLive) {
            System.err.println(
                "[FdEpoch Audit] WARNING: closing fd=$fd that was never marked as owned through this epoch. " +
                "This may be a foreign descriptor. Token was likely created via generic()/unsafe() " +
                "instead of adopt(). Set -Dmazewall.fd.audit=true to see this warning.",
            )
            // In audit mode, we still allow the close but log it
            // In strict mode (future), we could deny it
        }

        if (!isLive && !wasOwned) {
            System.err.println(
                "[FdEpoch Audit] WARNING: attempting to close fd=$fd that is neither live in epoch nor owned through epoch. " +
                "This is likely a bug - a token minted around a foreign integer.",
            )
        }

        return true
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
    val ownership: FdOwnership,
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
public class FileDescriptor<out R : FileDescriptorRole, out S : FdState, out O : FdOwnership> internal constructor(
    private val lifecycle: FdLifecycle,
) {
    public val value: Int get() = lifecycle.value
    public val arena: NativeArena? get() = lifecycle.arena
    internal val generation: Long get() = lifecycle.generation
    internal val role: FileDescriptorRole get() = lifecycle.role
    public val ownership: FdOwnership get() = lifecycle.ownership

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
    public fun isUsableAsDirfd(): Boolean = value == NativeConstants.AT_FDCWD || isLiveForIo()

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
        if (other !is FileDescriptor<*, *, *>) return false
        return value == other.value && generation == other.generation
    }

    override fun hashCode(): Int = 31 * value + generation.hashCode()

    public companion object {
        /**
         * Represents an invalid or uninitialized file descriptor.
         * Uses Nothing role to be compatible with all specific FD roles.
         */

        public val INVALID: FileDescriptor<Nothing, FdState.Closed, FdOwnership.Unowned> =
            FileDescriptor<FileDescriptorRole.Generic, FdState.Closed, FdOwnership.Unowned>(
                FdLifecycle(-1, null, generation = 0L, role = FileDescriptorRole.Generic, ownership = FdOwnership.Unowned, closed = true),
            ) as FileDescriptor<Nothing, FdState.Closed, FdOwnership.Unowned>

        /**
         * Sentinel for openat(2) [NativeConstants.AT_FDCWD]. Not a live kernel fd.
         */
        public val AT_FDCWD: FileDescriptor<FileDescriptorRole.OPath, FdState.Open, FdOwnership.Unowned> =
            FileDescriptor(
                FdLifecycle(NativeConstants.AT_FDCWD, null, generation = 0L, role = FileDescriptorRole.OPath, ownership = FdOwnership.Unowned, closed = false),
            )

        /**
         * Sentinel for anonymous mmap(2) (`fd = -1`). Not a live kernel fd.
         */
        public val ANON: FileDescriptor<FileDescriptorRole.Generic, FdState.Open, FdOwnership.Unowned> =
            FileDescriptor(
                FdLifecycle(-1, null, generation = 0L, role = FileDescriptorRole.Generic, ownership = FdOwnership.Unowned, closed = true),
            )

        /**
         * Unsafely creates a [FileDescriptor] from a raw integer.
         * Does NOT claim the FD in the epoch - for retired FDs, creates a non-live token.
         * Prefer the role-specific factories ([generic], [unixSocket], [ruleset], [oPath], [seccompNotif], [granted]).
         *
         * WARNING: This method will NOT revive retired file descriptors. For kernel-reused
         * integers from dup/accept/SCM_RIGHTS, use [adopt] or [replace] instead.
         *
         * DANGER (2026-08-24 incident): a token minted around an integer you do not own
         * is a loaded weapon. Calling [close] on it performs a real close(int) in this
         * JVM and destroys whatever happens to hold that number (/dev/urandom seed fd,
         * classloader jars, pipes), surfacing later as EBADF in unrelated code such as
         * Files.createTempDirectory. Only pass integers obtained from an open this
         * process owns; see platform test ForeignFdGuard for the enforcement guardrail.
         *
         * @param value The raw Linux file descriptor integer.
         * @param arena Optional arena bound to this descriptor's native lifetime.
         * @return A type-safe [FileDescriptor] in the [FdState.Open] state.
         */
        @Deprecated(
            message = "Use role-specific factories (generic, unixSocket, ruleset, oPath, seccompNotif, pid, granted) or adopt() for kernel-reused FDs. " +
                "This method creates non-live tokens for retired FDs and should not be used in production code.",
            level = DeprecationLevel.WARNING,
        )
        public fun <R : FileDescriptorRole> unsafe(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open, FdOwnership.Unowned> {
            if (value >= 0 && FdEpoch.isRetired(value)) {
                // Retired FD: mint a non-live token (generation 0, closed)
                // This prevents leftover Open tokens from operating on reused integers
                return FileDescriptor<FileDescriptorRole.Generic, FdState.Open, FdOwnership.Unowned>(
                    FdLifecycle(value, arena, 0L, FileDescriptorRole.Generic, ownership = FdOwnership.Unowned, closed = true),
                ) as FileDescriptor<R, FdState.Open, FdOwnership.Unowned>
            }
            // Non-retired or negative: delegate to open which will claim a new generation
            return open<FileDescriptorRole.Generic, FdOwnership.Unowned>(value, arena, FileDescriptorRole.Generic, FdOwnership.Unowned) as FileDescriptor<R, FdState.Open, FdOwnership.Unowned>
        }

        /**
         * Creates a Generic-role file descriptor token from a raw integer.
         * Does NOT claim ownership - the returned token is Unowned and cannot be closed.
         * For real FDs that this process owns, use [adopt] instead.
         *
         * WARNING: Only pass integers this process obtained from its own opens.
         * This creates an Unowned token; calling [close] on it will throw [IllegalStateException].
         * For invented integers or literals, prefer [replace] to create an Owned test token.
         */
        public fun generic(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.Generic, FdState.Open, FdOwnership.Unowned> = open(value, arena, FileDescriptorRole.Generic, FdOwnership.Unowned)

        public fun unixSocket(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Unowned> = open(value, arena, FileDescriptorRole.UnixSocket, FdOwnership.Unowned)

        public fun ruleset(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open, FdOwnership.Unowned> = open(value, arena, FileDescriptorRole.Ruleset, FdOwnership.Unowned)

        public fun oPath(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.OPath, FdState.Open, FdOwnership.Unowned> = open(value, arena, FileDescriptorRole.OPath, FdOwnership.Unowned)

        public fun seccompNotif(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Unowned> = open(value, arena, FileDescriptorRole.SeccompNotif, FdOwnership.Unowned)

        public fun pid(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.Pid, FdState.Open, FdOwnership.Unowned> = open(value, arena, FileDescriptorRole.Pid, FdOwnership.Unowned)

        public fun granted(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership.Unowned> = open(value, arena, FileDescriptorRole.Granted, FdOwnership.Unowned)

        /**
         * Adopts a newly allocated kernel fd (open, accept, dup, SCM_RIGHTS).
         *
         * Always installs a new generation: leftover [FdState.Open] tokens for this
         * integer, including still-live slots the wrapper never retired, become
         * dead. Ordinary [generic] / role factories keep the current generation
         * when the slot is already live (same resource, same owner).
         */
        public fun <R : FileDescriptorRole> adopt(
            value: Int,
            role: R,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open, FdOwnership.Owned> {
            val closed = value < 0
            val generation = if (closed) 0L else FdEpoch.adoptKernelReuse(value)
            if (value >= 0) {
                FdEpoch.markOwned(value)
            }
            return FileDescriptor(
                FdLifecycle(value, arena, generation, role, ownership = FdOwnership.Owned, closed = closed),
            )
        }

        /**
         * Kernel replaced this integer (dup2, SECCOMP_ADDFD SETFD).
         * Any leftover generation is retired first, then a new generation is claimed.
         */

        public fun <R : FileDescriptorRole> replace(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open, FdOwnership.Owned> {
            if (value >= 0) {
                FdEpoch.forceRetire(value)
            }
            // Claim a new generation for the replaced FD
            val result = open<FileDescriptorRole.Generic, FdOwnership.Owned>(value, arena, FileDescriptorRole.Generic, FdOwnership.Owned) as FileDescriptor<R, FdState.Open, FdOwnership.Owned>
            if (value >= 0) {
                FdEpoch.markOwned(value)
            }
            return result
        }

        private fun <R : FileDescriptorRole, O : FdOwnership> open(
            value: Int,
            arena: NativeArena?,
            role: FileDescriptorRole,
            ownership: O,
        ): FileDescriptor<R, FdState.Open, O> {
            val closed = value < 0
            val generation = if (closed) 0L else FdEpoch.claimOpen(value)
            return FileDescriptor(
                FdLifecycle(value, arena, generation, role, ownership, closed = closed),
            )
        }

        internal fun <R : FileDescriptorRole, O : FdOwnership> closedView(source: FileDescriptor<R, *, O>): FileDescriptor<R, FdState.Closed, O> {
            source.markClosed()
            return FileDescriptor(source.lifecycle)
        }
    }
}

/**
 * Fail-closed result for NativeEngine I/O when this token is not the live generation.
 */
public fun FileDescriptor<*, *, FdOwnership>.ebadfUnlessLive(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    if (isLiveForIo()) {
        return null
    }
    return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1L)
}

public fun FileDescriptor<*, *, FdOwnership>.ebadfUnlessDirfd(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    if (isUsableAsDirfd()) {
        return null
    }
    return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1L)
}

public fun FileDescriptor<*, *, FdOwnership>.ebadfUnlessMmapBacking(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    if (isUsableAsMmapBacking()) {
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

public fun LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>.claimDupIfNeeded(cmd: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
    if (this is LinuxNative.SyscallResult.Success &&
        (cmd == NativeConstants.F_DUPFD || cmd == NativeConstants.F_DUPFD_CLOEXEC) &&
        value >= 0L
    ) {
        FdEpoch.adoptKernelReuse(value.toInt())
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
 * **Ownership enforcement**: This method will throw [IllegalStateException] if called
 * on an Unowned descriptor (created via [unsafe], [generic], or role-specific factories).
 * Only descriptors created via [adopt], [replace], or [claimDupIfNeeded] can be closed.
 *
 * When audit mode is enabled (-Dmazewall.fd.audit=true), this method will warn
 * if closing a descriptor that was not marked as owned (created via [adopt] or [replace]).
 *
 * @return A new [FileDescriptor] instance with the same value but [FdState.Closed] state.
 * @throws IllegalStateException if this descriptor is Unowned.
 */
public fun <R : FileDescriptorRole, S : FdState.Open> FileDescriptor<R, S, FdOwnership.Owned>.close(): FileDescriptor<R, FdState.Closed, FdOwnership.Owned> {
    if (value >= 0 && !isClosedType()) {
        if (FdEpoch.auditClose(value, generation)) {
            LinuxNative.fileSystem.close(this as FileDescriptor<*, FdState.Open, FdOwnership.Owned>)
        }
        arena?.close()
    }
    return FileDescriptor.closedView(this)
}

/**
 * Executes the given [block] with this file descriptor and then closes it correctly,
 * even if an exception is thrown.
 */
public inline fun <R : FileDescriptorRole, S : FdState.Open, T> FileDescriptor<R, S, FdOwnership.Owned>.use(block: (FileDescriptor<R, S, FdOwnership.Owned>) -> T): T {
    try {
        return block(this)
    } finally {
        this.close()
    }
}
