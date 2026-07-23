package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.networking.SupervisorSocketUtils

internal object ProfilerSocket {
    fun connectWithRetry(
        socketPath: String,
        maxRetries: Int = 500,
        delayMs: Long = 10L,
    ): Int {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val sockaddrUn = SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)

            var lastErrno = 0
            for (retry in 0 until maxRetries) {
                val fdRes = LinuxNative.withTransaction {
                    LinuxNative.networking.socket(
                        SupervisorSocketUtils.AF_UNIX,
                        SupervisorSocketUtils.SOCK_STREAM or NativeConstants.SOCK_CLOEXEC,
                        0
                    )
                }
                val fdVal = when (fdRes) {
                    is LinuxNative.SyscallResult.Success -> fdRes.value.toInt()
                    is LinuxNative.SyscallResult.Error -> {
                        lastErrno = fdRes.errno
                        Thread.sleep(delayMs)
                        continue
                    }
                }
                val fd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(fdVal)
                val connRes = LinuxNative.withTransaction {
                    LinuxNative.networking.connect(
                        fd,
                        ConfinedSegment(sockaddrUn.segment),
                        SupervisorSocketUtils.SOCKADDR_UN_SIZE
                    )
                }
                if (connRes is LinuxNative.SyscallResult.Success) {
                    return fdVal
                }
                lastErrno = (connRes as LinuxNative.SyscallResult.Error).errno
                LinuxNative.fileSystem.close(fd)

                Thread.sleep(delayMs)
            }
            throw IllegalStateException(
                "Failed to connect to socket at $socketPath after $maxRetries retries. Last errno=$lastErrno"
            )
        }
    }

    fun sendDescriptor(
        socketFd: Int,
        fdToSend: Int,
    ): Boolean {
        return SupervisorSocketUtils.sendDescriptor(socketFd, fdToSend)
    }
}
