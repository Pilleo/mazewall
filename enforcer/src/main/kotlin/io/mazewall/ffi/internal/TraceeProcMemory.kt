package io.mazewall.ffi.internal

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.Arch
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.NativeArg
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena

/** Read-only positional access to an already opened /proc/task/mem capability; never a numeric pid lookup. */
internal class TraceeProcMemory(
    private val engine: NativeEngine,
) {
    context(arena: NativeArena) fun read(
        descriptor: FileDescriptor<*, FdState.Open, FdOwnership>,
        address: Long,
        length: Int,
    ): LinuxNative.SyscallResult<ByteArray, LinuxNative.SyscallHandledState.Unhandled> {
        require(length in 1..4096)
        val buffer = arena.allocate(length.toLong())
        return when (
            val result = engine.raw.syscall4(
                Arch.current().pread64.toLong(),
                NativeArg.FdArg(descriptor),
                NativeArg.MemoryArg(buffer),
                NativeArg.LongArg(length.toLong()),
                NativeArg.LongArg(address),
            )
        ) {
            is LinuxNative.SyscallResult.Error -> LinuxNative.SyscallResult.Error(result.errno, -1L)
            is LinuxNative.SyscallResult.Success -> {
                check(result.value in 0..length.toLong())
                val bytes = ByteArray(result.value.toInt())
                ManagedSegment.copy(buffer, 0, bytes, 0, bytes.size)
                LinuxNative.SyscallResult.Success(bytes)
            }
        }
    }
}
