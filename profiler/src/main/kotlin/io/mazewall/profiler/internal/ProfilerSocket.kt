package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.networking.SupervisorSocketUtils
import io.mazewall.getFdOrThrow

internal object ProfilerSocket {
    fun connectDescriptorWithRetry(
        socketPath: String,
        maxRetries: Int = 500,
        delayMs: Long = 10L,
    ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = connectDescriptorWithRetry(socketPath, LinuxNative.snapshot(), maxRetries, delayMs)

    fun connectDescriptorWithRetry(
        socketPath: String,
        native: NativeEngine,
        maxRetries: Int = 500,
        delayMs: Long = 10L,
    ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val sockaddrUn = SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)

            var lastErrno = 0

            for (retryAttempt in 0 until maxRetries) {
                val fdRes =
                native.networking.socket(
                    SupervisorSocketUtils.AF_UNIX,
                    SupervisorSocketUtils.SOCK_STREAM or NativeConstants.SOCK_CLOEXEC,
                    0,
                )

                when (fdRes) {
                    is LinuxNative.SyscallResult.Success -> Unit
                    is LinuxNative.SyscallResult.Error -> {
                        lastErrno = fdRes.errno
                        Thread.sleep(delayMs)
                        continue
                    }
                }
                val fd = fdRes.getFdOrThrow("socket", FileDescriptorRole.UnixSocket)
                val connRes =
                native.networking.connect(
                    fd,
                    ConfinedSegment(sockaddrUn.segment),
                    SupervisorSocketUtils.SOCKADDR_UN_SIZE,
                )

                if (connRes is LinuxNative.SyscallResult.Success) {
                    return fd
                }
                lastErrno = (connRes as LinuxNative.SyscallResult.Error).errno
                native.fileSystem.close(fd).getOrThrow("close failed profiler connection")

                Thread.sleep(delayMs)
            }
            throw IllegalStateException(
                "Failed to connect to socket at $socketPath after $maxRetries retries. Last errno=$lastErrno",
            )
        }
    }

    fun sendDescriptor(
        socketFd: Int,
        fdToSend: Int,
    ): Boolean = sendDescriptor(socketFd, fdToSend, LinuxNative.snapshot())

    fun sendDescriptor(
        socketFd: Int,
        fdToSend: Int,
        native: NativeEngine,
    ): Boolean {
        return SupervisorSocketUtils.sendDescriptor(socketFd, fdToSend, native.networking)
    }
}
