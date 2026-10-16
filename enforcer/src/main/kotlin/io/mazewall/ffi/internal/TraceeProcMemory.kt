package io.mazewall.ffi.internal

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg

/** Read-only positional access to an already opened /proc/task/mem capability; never a numeric pid lookup. */
internal class TraceeProcMemory(
    private val engine: NativeEngine,
) {
    context(arena: NativeArena) fun read(
        descriptor: FileDescriptor<*, FdState.Open, FdOwnership>,
        address: Long,
        length: Int,
    ): LinuxNative.SyscallResult<ByteArray, LinuxNative.SyscallHandledState.Unhandled> {
        require(length in 1..MAX_READ_BYTES)
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

    private companion object {
        const val MAX_READ_BYTES = 4096
    }
}
