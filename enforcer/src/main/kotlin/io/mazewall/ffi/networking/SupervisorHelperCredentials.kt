package io.mazewall.ffi.networking

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeInt
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg

/** Linux SO_PEERCRED is three 32-bit ints, not JVM longs. Verify the actual process launched by this owner. */
internal object SupervisorHelperCredentials {
    private const val CREDENTIAL_BYTES = 3 * Int.SIZE_BYTES
    private const val SOL_SOCKET = 1
    private const val SO_PEERCRED = 17

    fun verify(
        engine: NativeEngine,
        socket: FileDescriptor<*, FdState.Open, FdOwnership>,
        expectedPid: Long,
    ) {
        NativeArena.ofConfined().use { arena ->
            val credential = arena.allocate(CREDENTIAL_BYTES.toLong())
            val length = arena.allocate(Int.SIZE_BYTES.toLong()).also { it.writeInt(0, CREDENTIAL_BYTES) }
            engine.raw
                .syscall(
                    Arch.current().getsockopt.toLong(),
                    NativeArg.FdArg(socket),
                    NativeArg.IntArg(SOL_SOCKET),
                    NativeArg.IntArg(SO_PEERCRED),
                    NativeArg.MemoryArg(credential),
                    NativeArg.MemoryArg(length),
                ).getOrThrow("authenticate supervisor helper")
            check(length.readInt(0) == CREDENTIAL_BYTES && credential.readInt(0).toLong() == expectedPid) { "Supervisor helper peer identity mismatch" }
        }
    }
}
