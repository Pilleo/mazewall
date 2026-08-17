package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.NativeMemory
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment

/**
 * Stream sockets do not preserve message boundaries. Callers must loop until
 * [total] bytes move or a non-[EINTR] error / zero-length transfer occurs.
 */
public object SocketIo {
    public fun writeFully(
        memory: NativeMemory,
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        total: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        transferFully(total) { offset, remaining ->
            memory.write(fd, slice(buf, offset, remaining), remaining)
        }

    public fun readFully(
        memory: NativeMemory,
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        total: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        transferFully(total) { offset, remaining ->
            memory.read(fd, slice(buf, offset, remaining), remaining)
        }

    private fun slice(buf: ManagedSegment, offset: Long, remaining: Long): ManagedSegment =
        if (offset == 0L) buf.asSlice(0L, remaining) else buf.asSlice(offset, remaining)

    private fun transferFully(
        total: Long,
        op: (offset: Long, remaining: Long) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(total >= 0L) { "transfer length must be non-negative" }
        var offset = 0L
        while (offset < total) {
            val remaining = total - offset
            when (val res = op(offset, remaining)) {
                is LinuxNative.SyscallResult.Error -> {
                    if (res.errno == NativeConstants.EINTR) continue
                    return res
                }
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value <= 0L) {
                        return LinuxNative.SyscallResult.Error(NativeConstants.EIO, res.value)
                    }
                    offset += res.value
                }
            }
        }
        return LinuxNative.SyscallResult.Success(total)
    }
}
