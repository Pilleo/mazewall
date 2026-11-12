package io.mazewall.ffi.internal

import io.mazewall.enforcer.internal.supervisor.ConfinedHelperRejection
import io.mazewall.enforcer.internal.supervisor.SupervisorDescriptorResult
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal fun duplicateHelperResource(
    source: FileDescriptor<FileDescriptorRole.OPath, FdState.Open, FdOwnership.Owned>,
    native: NativeEngine,
): SupervisorOwnedFd =
    when (val result = native.raw.fcntl(source, NativeConstants.F_DUPFD_CLOEXEC, 0)) {
        is LinuxNative.SyscallResult.Success -> SupervisorOwnedFd(result.getFdOrThrow("duplicate helper resource", FileDescriptorRole.OPath), native.fileSystem)
        is LinuxNative.SyscallResult.Error -> throw ConfinedHelperRejection.nativeFailure("duplicate helper resource", result.errno)
    }

internal class OwnedProcFdReopen(
    private val native: NativeEngine,
    private val attributes: (Path) -> BasicFileAttributes,
) {
    fun acquire(
        root: FileDescriptor<FileDescriptorRole.OPath, FdState.Open, FdOwnership.Owned>,
        relative: String,
    ): SupervisorDescriptorResult {
        require(relative.isNotEmpty() && !relative.startsWith('/') && relative.split('/').none { it == ".." }) {
            "Expected a nonempty beneath-root suffix without parent traversal"
        }
        check(root.isLiveForIo()) { "Acquisition root has retired" }
        return NativeArena.ofConfined().use { arena ->
            val how = arena.allocate(Layouts.OPEN_HOW_SIZE)
            how.writeLong(Layouts.OPEN_HOW_FLAGS_OFFSET, (NativeConstants.O_PATH or NativeConstants.O_CLOEXEC).toLong())
            how.writeLong(Layouts.OPEN_HOW_MODE_OFFSET, 0L)
            how.writeLong(Layouts.OPEN_HOW_RESOLVE_OFFSET, NativeConstants.RESOLVE_BENEATH or NO_XDEV or NO_MAGICLINKS or NO_SYMLINKS)
            SupervisorDescriptorResult.from(native.fileSystem.openat2(root, arena.allocateFrom(relative), how, Layouts.OPEN_HOW_SIZE), native)
        }
    }

    fun reopenRegular(
        pinned: SupervisorOwnedFd,
        proc: SupervisorOwnedFd,
    ): SupervisorDescriptorResult {
        check(pinned.handle.isLiveForIo() && proc.handle.isLiveForIo()) { "Pinned object or trusted proc directory has retired" }
        val before = regularKey(pinned)
        val result = NativeArena.ofConfined().use { arena ->
            SupervisorDescriptorResult.from(
                native.fileSystem.openat(proc.handle, arena.allocateFrom(pinned.handle.value.toString()), OpenFlags(NativeConstants.O_RDONLY or NativeConstants.O_CLOEXEC), 0),
                native,
            )
        }
        if (result is SupervisorDescriptorResult.Opened) {
            try {
                require(regularKey(pinned) == before && regularKey(result.descriptor) == before) { "Pinned/readable object identity changed" }
            } catch (expectedIdentityFailure: Throwable) {
                try {
                    result.descriptor.close()
                } catch (expectedCloseFailure: Throwable) {
                    expectedIdentityFailure.addSuppressed(expectedCloseFailure)
                }
                throw expectedIdentityFailure
            }
        }
        return result
    }

    private fun regularKey(owner: SupervisorOwnedFd): Any {
        val attrs = attributes(Path.of("/proc/self/fd/${owner.handle.value}"))
        require(attrs.isRegularFile && !attrs.isDirectory && !attrs.isSymbolicLink && !attrs.isOther) { "Pinned object is not regular" }
        return requireNotNull(attrs.fileKey()) { "Pinned object has no identity" }
    }

    private companion object {
        const val NO_XDEV = 0x01L
        const val NO_MAGICLINKS = 0x02L
        const val NO_SYMLINKS = 0x04L
    }
}
