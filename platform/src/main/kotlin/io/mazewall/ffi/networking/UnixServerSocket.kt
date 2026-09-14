package io.mazewall.ffi.networking

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow

/** Owns native UNIX server acquisition until bind/listen and arena cleanup succeed. */
internal object UnixServerSocket {
    // Resource boundary: fatal and cancellation failures release ownership,
    // then propagate unchanged with cleanup failures attached.
    @Suppress("TooGenericExceptionCaught")
    fun open(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = open(socketPath, LinuxNative.snapshot())

    /** Opens a server socket using native authority captured by the caller. */
    @Suppress("TooGenericExceptionCaught")
    fun open(
        socketPath: String,
        native: NativeEngine,
    ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
        var owned: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        try {
            NativeArena.ofConfined().use { arena ->
                // Validate/prepare before acquisition, including bounds and allocation.
                val address = SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)
                val fd = native.networking
                    .socket(
                    SupervisorSocketUtils.AF_UNIX,
                    SupervisorSocketUtils.SOCK_STREAM or NativeConstants.SOCK_CLOEXEC,
                    0,
                ).getFdOrThrow("socket(AF_UNIX)", FileDescriptorRole.UnixSocket)
                owned = fd
                native.networking
                    .bind(fd, address.managed, SupervisorSocketUtils.SOCKADDR_UN_SIZE)
                    .getOrThrow("bind(AF_UNIX)")
                native.networking.listen(fd, SupervisorSocketUtils.BACKLOG_SIZE).getOrThrow("listen")
            }
            return checkNotNull(owned)
        } catch (failure: Throwable) {
            owned?.let { fd ->
                try {
                    native.fileSystem.close(fd).getOrThrow("close failed server")
                } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }
}
