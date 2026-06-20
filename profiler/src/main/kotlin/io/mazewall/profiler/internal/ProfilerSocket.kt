package io.mazewall.profiler.internal

import io.mazewall.ffi.networking.SupervisorSocketUtils
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

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
        arena: Arena,
        socketPath: String,
    ): MemorySegment {
        return SupervisorSocketUtils.setupSockAddrUn(arena, socketPath).segment
    }
}
