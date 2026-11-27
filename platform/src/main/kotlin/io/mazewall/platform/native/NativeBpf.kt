package io.mazewall.platform.native

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative.SyscallHandledState
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall

/** bpf(2) command path. The native adapter attaches a receipt to descriptor-producing results. */
internal interface NativeBpf {
    fun command(
        cmd: Long,
        attr: ManagedSegment,
        size: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>
}

internal fun NativeBpf.openDescriptor(
    cmd: BpfDescriptorCommand,
    attr: ManagedSegment,
    size: Long,
    context: String,
): FileDescriptor<FileDescriptorRole.Bpf, FdState.Open, FdOwnership.Owned> = command(cmd.code, attr, size).getFdOrThrow(context, FileDescriptorRole.Bpf)

internal fun RawSyscallOperations.asBpf(): NativeBpf = RawBackedBpf(this)

internal fun RawSyscallOperations.bpf(
    cmd: Long,
    attr: ManagedSegment,
    size: Long,
): SyscallResult<Long, SyscallHandledState.Unhandled> = asBpf().command(cmd, attr, size)

/** bpf(2) commands whose success value is a new file descriptor. */
internal enum class BpfDescriptorCommand(
    val code: Long,
) {
    MAP_CREATE(0L),
    PROG_LOAD(5L),
    OBJ_GET(7L),
    PROG_GET_FD_BY_ID(13L),
    MAP_GET_FD_BY_ID(14L),
    RAW_TRACEPOINT_OPEN(17L),
    BTF_LOAD(18L),
    BTF_GET_FD_BY_ID(19L),
    LINK_CREATE(28L),
    LINK_GET_FD_BY_ID(30L),
    ITER_CREATE(33L),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Long): BpfDescriptorCommand? = byCode[code]
    }
}

internal fun isBpfFdReturningCommand(cmd: Long): Boolean = BpfDescriptorCommand.fromCode(cmd) != null

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
