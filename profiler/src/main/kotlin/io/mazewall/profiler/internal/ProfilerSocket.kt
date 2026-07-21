package io.mazewall.profiler.internal

import io.mazewall.ffi.networking.SupervisorSocketUtils

internal object ProfilerSocket {
    fun connectWithRetry(
        socketPath: String,
        maxRetries: Int = 500,
        delayMs: Long = 10L,
    ): Int {
        return SupervisorSocketUtils.connectWithRetry(socketPath, maxRetries, delayMs)
    }

    fun sendDescriptor(
        socketFd: Int,
        fdToSend: Int,
    ): Boolean {
        return SupervisorSocketUtils.sendDescriptor(socketFd, fdToSend)
    }

    fun setupSockAddrUn(
        arena: io.mazewall.ffi.memory.NativeArena,
        socketPath: String,
    ): io.mazewall.ffi.memory.SockaddrUnSegment {
        return SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)
    }
}
