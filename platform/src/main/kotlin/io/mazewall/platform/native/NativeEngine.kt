package io.mazewall.platform.native

import io.mazewall.platform.process.*
import io.mazewall.platform.syscall.*

import io.mazewall.platform.fd.*

import io.mazewall.platform.fd.FdOwnership


import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.LinuxNative.SyscallHandledState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FdState
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.seccomp.BpfInstruction

/**
 * Interface for Linux native system calls and utility functions.
 * Decoupling this allows for mocking and fault injection in tests.
 *
 * DESIGN INVARIANT: This interface is decoupled from FFM implicit Arenas and
 * the [nativeScope] utility. To enable zero-allocation execution and clear memory
 * boundaries, methods requiring off-heap memory accept pre-allocated [ManagedSegment]
 * parameters exclusively. Callers are responsible for managing the lifecycles of their
 * own transient arenas (e.g., using iteration-level scopes in high-throughput reactor loops).
 */
internal interface NativeEngine {
    public val fileSystem: NativeFileSystem
    public val networking: NativeNetworking
    public val process: NativeProcess
    public val memory: NativeMemory
    public val raw: RawSyscallOperations
    public val bpf: NativeBpf get() = raw.asBpf()
}

/**
 * Interface for raw Linux system calls and low-level I/O operations.
 * Use with caution: these methods bypass high-level domain abstractions and
 * are intended for use only by core enforcer logic or specialized low-level components.
 */
internal interface RawSyscallOperations {
    fun syscall(
        nr: Long,
        a1: io.mazewall.platform.syscall.NativeArg = io.mazewall.platform.syscall.NativeArg.LongArg(0L),
        a2: io.mazewall.platform.syscall.NativeArg = io.mazewall.platform.syscall.NativeArg.LongArg(0L),
        a3: io.mazewall.platform.syscall.NativeArg = io.mazewall.platform.syscall.NativeArg.LongArg(0L),
        a4: io.mazewall.platform.syscall.NativeArg = io.mazewall.platform.syscall.NativeArg.LongArg(0L),
        a5: io.mazewall.platform.syscall.NativeArg = io.mazewall.platform.syscall.NativeArg.LongArg(0L),
        a6: io.mazewall.platform.syscall.NativeArg = io.mazewall.platform.syscall.NativeArg.LongArg(0L),
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun syscall4(
        nr: Long,
        a1: io.mazewall.platform.syscall.NativeArg,
        a2: io.mazewall.platform.syscall.NativeArg,
        a3: io.mazewall.platform.syscall.NativeArg,
        a4: io.mazewall.platform.syscall.NativeArg,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun <Req, Res> ioctl(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        command: IoctlCommand<Req, Res>,
        arg: Req,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> {
        val segment = when (arg) {
            is io.mazewall.ffi.TypedSegment<*> -> arg.segment
            is ManagedSegment -> arg
            else -> throw IllegalArgumentException("Unsupported ioctl argument type")
        }
        return ioctl(fd, command.code, segment)
    }

    fun ioctl(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        request: Long,
        arg: ManagedSegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun ioctl(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        request: Long,
        arg: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun fcntl(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        cmd: Int,
        arg: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun poll(
        fds: ManagedSegment,
        nfds: Long,
        timeout: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>
}

internal interface NativeFileSystem {
    fun open(
        path: ManagedSegment,
        flags: io.mazewall.platform.lifecycle.OpenFlags,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> = open(path, flags, 0)

    fun open(
        path: ManagedSegment,
        flags: io.mazewall.platform.lifecycle.OpenFlags,
        mode: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun openat(
        dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        path: ManagedSegment,
        flags: io.mazewall.platform.lifecycle.OpenFlags,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> = openat(dirfd, path, flags, 0)

    fun openat(
        dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        path: ManagedSegment,
        flags: io.mazewall.platform.lifecycle.OpenFlags,
        mode: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun openat2(
        dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        path: ManagedSegment,
        how: ManagedSegment,
        size: Long = 24L,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> =
        SyscallResult.Error(io.mazewall.ffi.NativeConstants.ENOSYS, -1L)

    fun readlink(
        path: ManagedSegment,
        buf: ManagedSegment,
        bufsiz: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun mmap(
        addr: Long,
        length: Long,
        prot: io.mazewall.platform.lifecycle.MmapProt,
        flags: io.mazewall.platform.lifecycle.MmapFlags,
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        offset: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): SyscallResult<Long, SyscallHandledState.Unhandled>
}

internal interface NativeNetworking {
    fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: ManagedSegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun bind(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        addr: ManagedSegment,
        addrlen: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun listen(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        backlog: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun accept(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun accept4(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun connect(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        addr: ManagedSegment,
        addrlen: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        msg: ManagedSegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        msg: ManagedSegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun recv(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: ManagedSegment,
        len: Long,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>
}

internal interface NativeProcess {
    fun gettid(): io.mazewall.platform.lifecycle.Tid

    fun prctl(command: io.mazewall.platform.syscall.PrctlCommand): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun pidfdOpen(
        pid: Int,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun pidfdGetFd(
        pidfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        targetFd: Int,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun archPrctl(code: Int, addr: Long): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun archPrctl(code: Int, addr: ManagedSegment): SyscallResult<Long, SyscallHandledState.Unhandled>
}

internal interface NativeMemory {
    fun processVmReadv(
        pid: io.mazewall.platform.lifecycle.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun processVmWritev(
        pid: io.mazewall.platform.lifecycle.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun read(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: ManagedSegment,
        count: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun write(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: ManagedSegment,
        count: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(arena: NativeArena)
    fun newSockFProg(
        filters: List<BpfInstruction>,
    ): ManagedSegment
}
