package io.mazewall.ffi

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.native.NativeEngine
import io.mazewall.seccomp.BpfInstruction

/** Allocates a BPF program only for the synchronous kernel operation that consumes it. */
internal object ScopedBpfProgram {
    inline fun <T> withProgram(
        filters: List<BpfInstruction>,
        native: NativeEngine,
        block: (ManagedSegment) -> T,
    ): T =
        NativeArena.ofConfined().use { arena ->
            block(with(arena) { native.memory.newSockFProg(filters) })
        }
}
