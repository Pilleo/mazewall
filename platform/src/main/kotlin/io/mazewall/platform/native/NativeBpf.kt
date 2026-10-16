package io.mazewall.platform.native

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.recordDescriptorAcquisition
import io.mazewall.platform.native.LinuxNative.SyscallHandledState
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall

/** bpf(2) command path. Fd-returning commands are adopted as [FileDescriptorRole.Bpf]. */
internal interface NativeBpf {
    fun command(
        cmd: Long,
        attr: ManagedSegment,
        size: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>
}

internal fun NativeBpf.openDescriptor(
    cmd: Long,
    attr: ManagedSegment,
    size: Long,
    context: String,
): FileDescriptor<FileDescriptorRole.Bpf, FdState.Open, FdOwnership.Owned> =
    command(cmd, attr, size)
        .recordDescriptorAcquisition()
        .getFdOrThrow(context, FileDescriptorRole.Bpf)

internal fun RawSyscallOperations.asBpf(): NativeBpf = RawBackedBpf(this)

internal fun RawSyscallOperations.bpf(
    cmd: Long,
    attr: ManagedSegment,
    size: Long,
): SyscallResult<Long, SyscallHandledState.Unhandled> = asBpf().command(cmd, attr, size)

/** bpf(2) commands whose success value is a new file descriptor. */
internal fun isBpfFdReturningCommand(cmd: Long): Boolean = cmd in BPF_FD_RETURNING_COMMANDS

private val BPF_FD_RETURNING_COMMANDS = setOf(
    0L, // BPF_MAP_CREATE
    5L, // BPF_PROG_LOAD
    7L, // BPF_OBJ_GET
    13L, // BPF_PROG_GET_FD_BY_ID
    14L, // BPF_MAP_GET_FD_BY_ID
    17L, // BPF_RAW_TRACEPOINT_OPEN
    18L, // BPF_BTF_LOAD
    19L, // BPF_BTF_GET_FD_BY_ID
    28L, // BPF_LINK_CREATE
    30L, // BPF_LINK_GET_FD_BY_ID
    33L, // BPF_ITER_CREATE
)

private class RawBackedBpf(
    private val raw: RawSyscallOperations,
) : NativeBpf {
    override fun command(
        cmd: Long,
        attr: ManagedSegment,
        size: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> {
        val nr = Syscall.BPF.numberFor(Arch.current()).toLong()
        check(nr > 0) { "bpf(2) is not available on this architecture" }
        return raw.syscall(
            nr,
            NativeArg.LongArg(cmd),
            NativeArg.MemoryArg(attr),
            NativeArg.LongArg(size),
        )
    }
}
