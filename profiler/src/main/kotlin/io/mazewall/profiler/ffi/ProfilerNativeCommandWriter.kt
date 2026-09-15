package io.mazewall.profiler.ffi

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeMemory

/** Owns the short-lived native buffer used for one-byte profiler control commands. */
internal object ProfilerNativeCommandWriter {
    /** Forces class resolution before a process-wide seccomp filter can block lazy loading. */
    fun preload() = Unit

    fun writeByte(
        memory: NativeMemory,
        socket: FileDescriptor<out FileDescriptorRole, FdState.Open, out FdOwnership>,
        command: Byte,
    ): LinuxNative.SyscallResult<Long, *> =
        NativeArena.ofConfined().use { arena ->
            val buffer = arena.allocate(1)
            ManagedSegment.copy(byteArrayOf(command), 0, buffer, 0, 1)
            memory.write(socket, buffer, 1)
        }
}
