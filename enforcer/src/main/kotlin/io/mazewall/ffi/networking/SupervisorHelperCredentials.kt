package io.mazewall.ffi.networking

import io.mazewall.NativeEngine
import io.mazewall.core.Arch
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.NativeArg
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeInt

/** Linux SO_PEERCRED is three 32-bit ints, not JVM longs. Verify the actual process launched by this owner. */
internal object SupervisorHelperCredentials {
    fun verify(
        engine: NativeEngine,
        socket: FileDescriptor<*, FdState.Open, FdOwnership>,
        expectedPid: Long,
    ) {
        NativeArena.ofConfined().use { arena ->
            val credential = arena.allocate(12L)
            val length = arena.allocate(4L).also { it.writeInt(0, 12) }
            engine.raw
                .syscall(Arch.current().getsockopt.toLong(), NativeArg.FdArg(socket), NativeArg.IntArg(1), NativeArg.IntArg(17), NativeArg.MemoryArg(credential), NativeArg.MemoryArg(length))
                .getOrThrow("authenticate supervisor helper")
            check(length.readInt(0) == 12 && credential.readInt(0).toLong() == expectedPid) { "Supervisor helper peer identity mismatch" }
        }
    }
}
