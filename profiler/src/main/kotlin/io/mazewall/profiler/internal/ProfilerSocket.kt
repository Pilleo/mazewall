package io.mazewall.profiler.internal

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.networking.SupervisorSocketUtils
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow

internal object ProfilerSocket {
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
        native: NativeEngine,
    ): Boolean {
        return SupervisorSocketUtils.sendDescriptor(socketFd, fdToSend, native.networking)
    }
}
