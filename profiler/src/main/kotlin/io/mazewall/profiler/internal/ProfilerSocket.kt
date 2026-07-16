package io.mazewall.profiler.internal

import io.mazewall.ffi.networking.SupervisorSocketUtils
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.ManagedSegment

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
        arena: NativeArena,
        socketPath: String,
    ): ManagedSegment {
        return SupervisorSocketUtils.setupSockAddrUn(arena, socketPath).managed
    }
}
