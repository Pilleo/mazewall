package io.mazewall.profiler.ffi

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.ConfinedSegment
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

/** Owns the short-lived native buffer used for one-byte profiler control commands. */
internal object ProfilerNativeCommandWriter {
    fun writeByte(
        engine: NativeEngine,
        socket: FileDescriptor<out FileDescriptorRole, FdState.Open, out FdOwnership>,
        command: Byte,
    ): LinuxNative.SyscallResult<Long, *> =
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(1)
            buffer.set(ValueLayout.JAVA_BYTE, 0L, command)
            engine.memory.write(socket, ConfinedSegment(buffer), 1)
        }
}
