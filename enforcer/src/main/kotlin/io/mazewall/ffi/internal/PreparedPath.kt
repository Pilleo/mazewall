package io.mazewall.ffi.internal

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.ResourceRetirement
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A genuine O_PATH acquisition, not a pathname promise. Linux proc metadata is required. */
internal class PreparedPath private constructor(
    val requested: Path,
    val resolved: Path,
    val descriptor: FileDescriptor<FileDescriptorRole.OPath, FdState.Open, FdOwnership.Owned>,
    val directory: Boolean,
    private val key: Any,
    private val native: NativeEngine,
) : AutoCloseable {
    val identity: String get() = "$requested\u0000$resolved\u0000$key\u0000$directory"

    fun validate() {
        check(descriptor.isLiveForIo()) { "Prepared descriptor has retired: $requested" }
        check(requested.toRealPath() == resolved) { "Prepared symlink resolution changed: $requested" }
        val current = Files.readAttributes(requested, BasicFileAttributes::class.java)
        val pinned = Files.readAttributes(descriptorPath(), BasicFileAttributes::class.java)
        check(current.fileKey() == key && pinned.fileKey() == key && current.isDirectory == directory) {
            "Prepared filesystem object was replaced: $requested"
        }
    }

    fun descriptorPath(): Path = Path.of("/proc/self/fd/${descriptor.value}")

    override fun close() {
        if (descriptor.isLiveForIo()) {
            val result = native.fileSystem.close(descriptor)
            descriptor.markClosed()
            result.getOrThrow("close prepared resource $requested")
        }
    }

    internal class HelperSlot(
        private val capture: io.mazewall.enforcer.internal.execution.HelperSupportCapture,
        private val native: NativeEngine,
        private val path: Path,
    ) {
        private enum class CloseState { NotAttempted, Dispatched, Complete, Unknown }

        private var descriptor: FileDescriptor<FileDescriptorRole.OPath, FdState.Open, FdOwnership.Owned>? = null
        private var state = CloseState.NotAttempted
        private var failure: Throwable? = null

        internal data class CloseFailure(
            val cause: Throwable,
            val newlyDispatched: Boolean,
        )

        fun open(): PreparedPath {
            check(capture.permits(this, native, cleanup = false)) { "Foreign helper support slot" }
            check(descriptor == null && state == CloseState.NotAttempted)
            val requested = path.toAbsolutePath().normalize()
            val resolved = requested.toRealPath()
            val before = Files.readAttributes(resolved, BasicFileAttributes::class.java)
            require(before.isDirectory || before.isRegularFile) { "Prepared resource must be a regular file or directory: $requested" }
            val identity = checkNotNull(before.fileKey()) { "Filesystem has no stable object identity: $requested" }
            NativeArena.ofConfined().use { arena ->
                descriptor = native.fileSystem
                    .open(arena.allocateFrom(resolved.toString()), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or NativeConstants.O_NOFOLLOW))
                    .getFdOrThrow("open prepared resource $requested", FileDescriptorRole.OPath)
            }
            return PreparedPath(requested, resolved, checkNotNull(descriptor), before.isDirectory, identity, native).also { it.validate() }
        }

        @Suppress("TooGenericExceptionCaught") // Retain Unknown and original fatal identity after close dispatch.
        fun closeOwned(): CloseFailure? {
            check(capture.permits(this, native, cleanup = true)) { "Foreign helper support cleanup" }
            return when (state) {
                CloseState.Complete -> null
                CloseState.Unknown -> CloseFailure(checkNotNull(failure), false)
                CloseState.Dispatched -> error("Helper support close is in progress")
                CloseState.NotAttempted -> descriptor?.let { owned ->
                    state = CloseState.Dispatched
                    try {
                        native.fileSystem.close(owned).getOrThrow("close helper support $path")
                        owned.markClosed()
                        state = CloseState.Complete
                        null
                    } catch (problem: Throwable) {
                        failure = problem
                        state = CloseState.Unknown
                        CloseFailure(problem, true)
                    }
                }
            }
        }
    }

    internal class ManagedSlot(
        private val runtime: io.mazewall.enforcer.api.execution.ManagedRuntime,
        private val key: Any,
        private val record: Any,
        private val capture: Any,
        private val native: NativeEngine,
        private val path: Path,
        private val afterStore: () -> Unit,
    ) {
        private var descriptor: FileDescriptor<FileDescriptorRole.OPath, FdState.Open, FdOwnership.Owned>? = null
        private var dispatched = false
        private var started = false

        private fun requireOwner(cleanup: Boolean) {
            check(
                io.mazewall.enforcer.api.execution
                .matchesManagedPath(runtime, key, record, capture, this, native, cleanup),
            ) { "Foreign managed path slot" }
        }

        fun open(): PreparedPath {
            requireOwner(false)
            check(!started && descriptor == null && !dispatched)
            started = true
            val requested = path.toAbsolutePath().normalize()
            val resolved = requested.toRealPath()
            val before = Files.readAttributes(resolved, BasicFileAttributes::class.java)
            require(before.isDirectory || before.isRegularFile) { "Prepared resource must be a regular file or directory: $requested" }
            val identity = checkNotNull(before.fileKey()) { "Filesystem has no stable object identity: $requested" }
            NativeArena.ofConfined().use { arena ->
                descriptor = native.fileSystem
                    .open(arena.allocateFrom(resolved.toString()), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or NativeConstants.O_NOFOLLOW))
                    .getFdOrThrow("open prepared resource $requested", FileDescriptorRole.OPath)
                afterStore()
            }
            val result = PreparedPath(requested, resolved, checkNotNull(descriptor), before.isDirectory, identity, native)
            result.validate()
            return result
        }

        fun closeOwned() {
            requireOwner(true)
            val owned = descriptor ?: return
            check(!dispatched) { "Managed path close already dispatched" }
            dispatched = true
            native.fileSystem.close(owned).getOrThrow("close prepared resource")
            owned.markClosed()
            descriptor = null
        }
    }

    internal class DirectSlot(
        private val owner: io.mazewall.enforcer.api.execution.DirectResolutionCapture,
        private val native: NativeEngine,
        private val path: Path,
    ) {
        private var descriptor: FileDescriptor<FileDescriptorRole.OPath, FdState.Open, FdOwnership.Owned>? = null
        private var dispatched = false

        fun open(): PreparedPath {
            check(owner.permits(this, native, false))
            check(descriptor == null && !dispatched)
            val requested = path.toAbsolutePath().normalize()
            val resolved = requested.toRealPath()
            val before = Files.readAttributes(resolved, BasicFileAttributes::class.java)
            require(before.isDirectory || before.isRegularFile)
            val identity = checkNotNull(before.fileKey())
            NativeArena.ofConfined().use { arena ->
                descriptor = native.fileSystem
                    .open(arena.allocateFrom(resolved.toString()), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or NativeConstants.O_NOFOLLOW))
                    .getFdOrThrow("open prepared resource $requested", FileDescriptorRole.OPath)
            }
            return PreparedPath(requested, resolved, checkNotNull(descriptor), before.isDirectory, identity, native).also { it.validate() }
        }

        fun closeOwned() {
            check(owner.permits(this, native, true))
            val owned = descriptor ?: return
            check(!dispatched)
            dispatched = true
            native.fileSystem.close(owned).getOrThrow("close prepared resource")
            owned.markClosed()
            descriptor = null
        }
    }

    companion object {
        fun open(
            path: Path,
            native: NativeEngine,
        ): PreparedPath {
            val requested = path.toAbsolutePath().normalize()
            val resolved = requested.toRealPath()
            val before = Files.readAttributes(resolved, BasicFileAttributes::class.java)
            require(before.isDirectory || before.isRegularFile) { "Prepared resource must be a regular file or directory: $requested" }
            val key = checkNotNull(before.fileKey()) { "Filesystem has no stable object identity: $requested" }
            val descriptor = NativeArena.ofConfined().use { arena ->
                native.fileSystem
                    .open(arena.allocateFrom(resolved.toString()), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or NativeConstants.O_NOFOLLOW))
                    .getFdOrThrow("open prepared resource $requested", FileDescriptorRole.OPath)
            }
            val result = PreparedPath(requested, resolved, descriptor, before.isDirectory, key, native)
            return ResourceRetirement.runWithRollback({
                result.validate()
                result
            }, { result.close() })
        }
    }
}
