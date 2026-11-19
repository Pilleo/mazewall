package io.mazewall.ffi

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.native.NativeEngine
import io.mazewall.seccomp.BpfProgram
import io.mazewall.seccomp.BpfStatus

/** Allocates a BPF program only for the synchronous kernel operation that consumes it. */
internal object ScopedBpfProgram {
    inline fun <T> withProgram(
        program: BpfProgram<BpfStatus.Verified>,
        native: NativeEngine,
        block: (ManagedSegment) -> T,
    ): T =
        NativeArena.ofConfined().use { arena ->
            block(with(arena) { native.memory.newSockFProg(program.instructions) })
        }
}
