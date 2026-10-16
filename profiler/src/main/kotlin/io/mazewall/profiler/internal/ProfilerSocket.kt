package io.mazewall.profiler.internal

import io.mazewall.ffi.networking.SupervisorSocketUtils
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.NativeEngine

internal object ProfilerSocket {
    fun connectDescriptorWithRetry(
        socketPath: String,
        native: NativeEngine,
        maxRetries: Int = 500,
        delayMs: Long = 10L,
    ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = SupervisorSocketUtils.connectDescriptorWithRetry(socketPath, native, maxRetries, delayMs)

    fun sendDescriptor(
        socketFd: Int,
        fdToSend: Int,
        native: NativeEngine,
    ): Boolean {
        return SupervisorSocketUtils.sendDescriptor(socketFd, fdToSend, native.networking)
    }
}
