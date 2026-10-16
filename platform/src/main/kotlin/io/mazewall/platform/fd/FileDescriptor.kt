package io.mazewall.platform.fd

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.NativeArg
import java.util.concurrent.ConcurrentHashMap

/**
 * Marker interfaces for File Descriptor lifecycle states.
 */
internal sealed interface FdState {
    /** The descriptor is open and valid for I/O operations. */
    public interface Open : FdState

    /** The descriptor has been closed and is no longer valid. */
    public interface Closed : FdState
}

/**
 * Marker interfaces for File Descriptor roles to provide compile-time safety.
 */
internal sealed interface FileDescriptorRole {
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

    /** A bpf(2) map, program, BTF, link, or related perf event. */
    public data object Bpf : FileDescriptorRole
}

/**
 * Ownership status of a file descriptor.
 *
 * - **Owned**: The descriptor was created by consuming a native acquisition receipt.
 *   It represents a real file descriptor that this code owns and is responsible for closing.
 * - **Unowned**: The descriptor was created via [unsafe], [generic], or role-specific factories
 *   like [unixSocket], [seccompNotif], etc. It represents an invented integer or a descriptor
 *   owned by another component. Closing an Unowned descriptor will throw [IllegalStateException].
 */
internal sealed interface FdOwnership {
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
        val owned: Boolean,
    )

    private val sequence = java.util.concurrent.atomic
        .AtomicLong()
    private val table = ConcurrentHashMap<Int, Slot>()

    fun claimOpen(fd: Int): Long = if (fd < 0) 0L else table.computeIfAbsent(fd) { Slot(nextIdentity(), false) }.generation

    fun borrowLiveGeneration(fd: Int): Long = if (fd < 0) 0L else table[fd]?.generation ?: 0L

    fun adoptKernelReuse(fd: Int): Long {
        if (fd < 0) return 0L
        val identity = nextIdentity()
        table[fd] = Slot(identity, true)
        return identity
    }

    private fun nextIdentity(): Long =
        sequence.updateAndGet { previous ->
            check(previous < Long.MAX_VALUE) { "Descriptor identity space exhausted" }
            previous + 1
        }

    fun retire(
        fd: Int,
        generation: Long,
    ): Boolean {
        val slot = table[fd] ?: return false
        return slot.generation == generation && table.remove(fd, slot)
    }

    fun isLive(
        fd: Int,
        generation: Long,
    ): Boolean = generation != 0L && table[fd]?.generation == generation

    // No tombstones: a retired identity is absent from the active registry.
    fun isRetired(fd: Int): Boolean = fd >= 0 && !table.containsKey(fd)

    fun isOwnedThroughEpoch(
        fd: Int,
        generation: Long,
    ): Boolean = table[fd]?.let { it.generation == generation && it.owned } ?: false

    fun verifyKernelLiveness(fd: Int): Boolean = LinuxNative.isDescriptorLive(fd)

    fun auditClose(
        fd: Int,
        generation: Long,
    ): Boolean =
        isOwnedThroughEpoch(fd, generation) &&
            (System.getProperty("mazewall.fd.audit")?.lowercase() != "true" || verifyKernelLiveness(fd))
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
internal class FileDescriptor<out R : FileDescriptorRole, out S : FdState, out O : FdOwnership> internal constructor(
    private val lifecycle: FdLifecycle,
) {
    public val value: Int get() = lifecycle.value
    public val arena: NativeArena? get() = lifecycle.arena
    internal val generation: Long get() = lifecycle.generation
    private var viewRole: FileDescriptorRole? = null
    internal val role: FileDescriptorRole get() = viewRole ?: lifecycle.role
    public val ownership: FdOwnership get() = lifecycle.ownership

    /** Returns true if the file descriptor is open and valid. */
    public val isValid: Boolean
        get() {
            val owner = lifecycle.arena
            return value >= 0 &&
                (owner == null || owner.isAlive) &&
                !lifecycle.closed &&
                (if (lifecycle.generation == 0L) lifecycle.ownership !is FdOwnership.Owned else FdEpoch.isLive(value, lifecycle.generation))
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

    /**
     * Atomically retires exactly one native close attempt, including failed close(2).
     *
     * Linux `close(2)` that returns `EINTR` still closes the integer; retrying it can
     * close a reused descriptor. Native dispatch therefore retires the token even when
     * the syscall result is an error. Callers must not retry the same generation.
     */
    internal fun claimForClose(): Boolean {
        val claimed = FdEpoch.retire(value, generation)
        if (claimed) lifecycle.closed = true
        return claimed
    }

    /**
     * Dispatches close(2) and then forgets this generation.
     *
     * Linux still releases the integer when close(2) returns `EINTR`, so a later
     * retry can close a reused fd. The token is retired even if the syscall result
     * is an error; see docs/internals/designs/core/fd-token-ownership.md.
     */
    internal fun closeResource(native: io.mazewall.platform.native.NativeFileSystem = LinuxNative.snapshot().fileSystem) {
        synchronized(lifecycle) {
            if (value < 0 || isClosedType() || !isLiveForIo()) return
            try {
                native.close(this as FileDescriptor<*, FdState.Open, FdOwnership.Owned>)
            } finally {
                markClosed()
                arena?.close()
            }
        }
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
         * integers from dup/accept/SCM_RIGHTS, retain and consume the native acquisition result instead.
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
            message = "Use role-specific factories for borrowed descriptors, or getFdOrThrow on a native acquisition result for owned descriptors. " +
                "This method creates non-live tokens for retired FDs and should not be used in production code.",
            level = DeprecationLevel.ERROR,
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
         * For real FDs that this process owns, consume the native acquisition result with getFdOrThrow.
         *
         * WARNING: Only pass integers this process obtained from its own opens.
         * This creates an Unowned token; calling [close] on it will throw [IllegalStateException].
         * Test-only owned fixtures belong in shared test sources and must never be passed to the real engine.
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
         * Binary compatibility shim. Raw integers carry no evidence of ownership.
         * Consume the result of a native acquisition with getFdOrThrow instead.
         */
        @Deprecated("Raw descriptor adoption is disabled; consume a native acquisition result with getFdOrThrow")
        // SUPPRESSION-RATIONALE: Binary migration signature is retained, but raw integers always fail before ownership can be fabricated.
        @Suppress("UnusedParameter")
        public fun <R : FileDescriptorRole> adopt(
            value: Int,
            role: R,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open, FdOwnership.Owned> = error("Raw descriptor adoption is disabled; consume a native acquisition result with getFdOrThrow")

        @Deprecated("Raw descriptor replacement is disabled; use a native acquisition receipt")
        // SUPPRESSION-RATIONALE: Binary migration signature is retained, but replacement always fails before touching any descriptor.
        @Suppress("UnusedParameter")
        public fun <R : FileDescriptorRole> replace(
            value: Int,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open, FdOwnership.Owned> = error("Raw descriptor replacement is disabled; use a native acquisition receipt")

        @JvmSynthetic
        internal fun <R : FileDescriptorRole> acquired(
            value: Int,
            role: R,
            arena: NativeArena? = null,
        ): FileDescriptor<R, FdState.Open, FdOwnership.Owned> = FileDescriptor(FdLifecycle(value, arena, FdEpoch.adoptKernelReuse(value), role, FdOwnership.Owned, value < 0))

        /** Creates another typed view of an already owned capability, without adopting an integer. */
        public fun <R : FileDescriptorRole> withRole(
            source: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
            role: R,
        ): FileDescriptor<R, FdState.Open, FdOwnership.Owned> = FileDescriptor<R, FdState.Open, FdOwnership.Owned>(source.lifecycle).also { it.viewRole = role }

        private fun <R : FileDescriptorRole, O : FdOwnership> open(
            value: Int,
            arena: NativeArena?,
            role: FileDescriptorRole,
            ownership: O,
        ): FileDescriptor<R, FdState.Open, O> {
            val closed = value < 0
            val generation = if (closed) {
                0L
            } else if (ownership is FdOwnership.Owned) {
                FdEpoch.claimOpen(value)
            } else {
                // Unowned descriptor: borrow active generation if already tracked, otherwise 0L (never leak entries into FdEpoch.table)
                FdEpoch.borrowLiveGeneration(value)
            }
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
internal fun FileDescriptor<*, *, FdOwnership>.ebadfUnlessLive(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    if (isLiveForIo()) {
        return null
    }
    return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1L)
}

internal fun FileDescriptor<*, *, FdOwnership>.ebadfUnlessDirfd(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    if (isUsableAsDirfd()) {
        return null
    }
    return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1L)
}

internal fun FileDescriptor<*, *, FdOwnership>.ebadfUnlessMmapBacking(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    if (isUsableAsMmapBacking()) {
        return null
    }
    return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1L)
}

internal fun NativeArg.ebadfUnlessLive(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    return if (this is NativeArg.FdArg) fd.ebadfUnlessLive() else null
}

internal fun ebadfUnlessLive(vararg args: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? {
    for (arg in args) {
        val denied = arg.ebadfUnlessLive()
        if (denied != null) {
            return denied
        }
    }
    return null
}

// SUPPRESSION-RATIONALE: Legacy linkage retains cmd; ownership now originates exclusively in the native acquisition receipt and this adapter is identity-only.
@Suppress("UnusedParameter")
internal fun LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>.claimDupIfNeeded(cmd: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
    // Compatibility helper only. Actual acquisition receipts are minted by native fcntl.

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
 * Only descriptors obtained from native acquisition receipts can be closed.
 * Concurrent typed views share a lifecycle lock; native dispatch atomically retires
 * the active identity so even a failed native close cannot be retried.
 *
 * @return A new [FileDescriptor] instance with the same value but [FdState.Closed] state.
 * @throws IllegalStateException if this descriptor is Unowned.
 */
internal fun <R : FileDescriptorRole, S : FdState.Open> FileDescriptor<R, S, FdOwnership.Owned>.close(
    native: io.mazewall.platform.native.NativeFileSystem = LinuxNative.snapshot().fileSystem,
): FileDescriptor<R, FdState.Closed, FdOwnership.Owned> {
    closeResource(native)

    return FileDescriptor.closedView(this)
}

/**
 * Executes the given [block] with this file descriptor and then closes it correctly,
 * even if an exception is thrown.
 */
@Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally") // Preserve the primary failure and attach a native-close failure as evidence.
internal fun <R : FileDescriptorRole, S : FdState.Open, T> FileDescriptor<R, S, FdOwnership.Owned>.use(
    native: io.mazewall.platform.native.NativeFileSystem = LinuxNative.snapshot().fileSystem,
    block: (FileDescriptor<R, S, FdOwnership.Owned>) -> T,
): T {
    var blockFailure: Throwable? = null
    try {
        return block(this)
    } catch (failure: Throwable) {
        blockFailure = failure
        throw failure
    } finally {
        try {
            close(native)
        } catch (closeFailure: Throwable) {
            val prior = blockFailure
            if (prior != null) prior.addSuppressed(closeFailure) else throw closeFailure
        }
    }
}

/** Java try-with-resources adapter for an owned open descriptor. */
internal fun FileDescriptor<*, FdState.Open, FdOwnership.Owned>.asAutoCloseable(): AutoCloseable = AutoCloseable { close() }
