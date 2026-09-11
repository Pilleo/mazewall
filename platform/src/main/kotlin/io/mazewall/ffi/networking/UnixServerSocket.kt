package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.getFdOrThrow

/** Owns native UNIX server acquisition until bind/listen and arena cleanup succeed. */
internal object UnixServerSocket {
    // Resource boundary: fatal and cancellation failures release ownership,
    // then propagate unchanged with cleanup failures attached.
    @Suppress("TooGenericExceptionCaught")
    fun open(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
        val runtime = LinuxNative.snapshot()
        var owned: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        try {
            NativeArena.ofConfined().use { arena ->
                // Validate/prepare before acquisition, including bounds and allocation.
                val address = SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)
                val fd = runtime.networking
                    .socket(
                    SupervisorSocketUtils.AF_UNIX,
                    SupervisorSocketUtils.SOCK_STREAM or NativeConstants.SOCK_CLOEXEC,
                    0,
                ).getFdOrThrow("socket(AF_UNIX)", FileDescriptorRole.UnixSocket)
                owned = fd
                runtime.networking
                    .bind(fd, address.managed, SupervisorSocketUtils.SOCKADDR_UN_SIZE)
                    .getOrThrow("bind(AF_UNIX)")
                runtime.networking.listen(fd, SupervisorSocketUtils.BACKLOG_SIZE).getOrThrow("listen")
            }
            return checkNotNull(owned)
        } catch (failure: Throwable) {
            owned?.let { fd ->
                try {
                    runtime.fileSystem.close(fd).getOrThrow("close failed server")
                } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }
}
