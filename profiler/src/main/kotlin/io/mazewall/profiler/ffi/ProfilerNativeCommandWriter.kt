package io.mazewall.profiler.ffi

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.unwrap
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeMemory
import io.mazewall.platform.seccomp.daemon.SeccompStreamEnd
import io.mazewall.profiler.internal.TraceStreamEndWire

/** Owns the short-lived native buffer used for one-byte profiler control commands. */
internal object ProfilerNativeCommandWriter {
    /** Forces class resolution before a process-wide seccomp filter can block lazy loading. */
    fun preload() = Unit

    /** One nonblocking write: failure/short delivery leaves the listener's evidence incomplete. */
    fun writeStreamEnd(
        io: NativeIoOperations,
        socket: FileDescriptor<*, FdState.Open, FdOwnership>,
        reason: SeccompStreamEnd,
    ) {
        val flags = io.raw.fcntl(socket, NativeConstants.F_GETFL, 0).getOrThrow("read trace socket flags")
        io.raw.fcntl(socket, NativeConstants.F_SETFL, flags or NativeConstants.O_NONBLOCK).getOrThrow("set trace socket nonblocking")
        val bytes = TraceStreamEndWire.encode(reason)
        NativeArena.ofConfined().use { arena ->
            val buffer = arena.allocate(bytes.size.toLong())
            ManagedSegment.copy(bytes, 0, buffer, 0, bytes.size)
            val written = io.write(socket, buffer.unwrap, bytes.size.toLong()).getOrThrow("write trace terminal record")
            check(written == bytes.size.toLong()) { "Short trace terminal record" }
        }
    }

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
