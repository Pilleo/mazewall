package io.mazewall

import io.mazewall.LinuxNative.SyscallResult
import io.mazewall.LinuxNative.SyscallHandledState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.seccomp.BpfInstruction
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * A mock implementation of [NativeEngine] for testing fault injection.
 */
public open class MockNativeEngine(
    override val fileSystem: MockNativeFileSystem = MockNativeFileSystem(),
    override val networking: MockNativeNetworking = MockNativeNetworking(),
    override val process: MockNativeProcess = MockNativeProcess(),
    override val memory: MockNativeMemory = MockNativeMemory(),
) : NativeEngine {
    public var syscallResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var ioctlResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var fcntlResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var pollResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)

    public var onSyscall: ((nr: Long, a1: io.mazewall.core.NativeArg, a2: io.mazewall.core.NativeArg, a3: io.mazewall.core.NativeArg, a4: io.mazewall.core.NativeArg, a5: io.mazewall.core.NativeArg, a6: io.mazewall.core.NativeArg) -> SyscallResult<Long, SyscallHandledState.Unhandled>)? = null

    context(tx: NativeTransaction)
    override fun syscall(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
        a5: io.mazewall.core.NativeArg,
        a6: io.mazewall.core.NativeArg,
    ) = onSyscall?.invoke(nr, a1, a2, a3, a4, a5, a6) ?: syscallResult

    context(tx: NativeTransaction)
    override fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ) = onSyscall?.invoke(nr, a1, a2, a3, a4, io.mazewall.core.NativeArg.LongArg(0L), io.mazewall.core.NativeArg.LongArg(0L)) ?: syscallResult

    context(tx: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: MemorySegment,
    ) = ioctlResult

    context(tx: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ) = ioctlResult

    context(tx: NativeTransaction)
    override fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ) = fcntlResult

    context(tx: NativeTransaction)
    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ) = pollResult
}

public open class MockNativeFileSystem : NativeFileSystem {
    public var openResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var readlinkResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var closeResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var mmapResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)

    context(tx: NativeTransaction)
    override fun open(
        path: MemorySegment,
        flags: Int,
    ) = openResult

    context(tx: NativeTransaction)
    override fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ) = readlinkResult

    context(tx: NativeTransaction)
    override fun mmap(
        addr: Long,
        length: Long,
        prot: Int,
        flags: Int,
        fd: Int,
        offset: Long,
    ) = mmapResult

    override fun close(fd: FileDescriptor<*, FdState.Open>) = closeResult
}

public open class MockNativeNetworking : NativeNetworking {
    public var socketpairResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var socketResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var bindResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var listenResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var acceptResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var connectResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var sendmsgResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var recvmsgResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var recvResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)

    public var onSendmsg: ((sockfd: FileDescriptor<*, FdState.Open>, msg: MemorySegment, flags: Int) -> SyscallResult<Long, SyscallHandledState.Unhandled>)? = null
    public var onRecvmsg: ((sockfd: FileDescriptor<*, FdState.Open>, msg: MemorySegment, flags: Int) -> SyscallResult<Long, SyscallHandledState.Unhandled>)? = null

    context(tx: NativeTransaction)
    override fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ) = socketpairResult

    context(tx: NativeTransaction)
    override fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ) = socketResult

    context(tx: NativeTransaction)
    override fun bind(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: Int,
    ) = bindResult

    context(tx: NativeTransaction)
    override fun listen(
        sockfd: FileDescriptor<*, FdState.Open>,
        backlog: Int,
    ) = listenResult

    context(tx: NativeTransaction)
    override fun accept(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ) = acceptResult

    context(tx: NativeTransaction)
    override fun connect(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: Int,
    ) = connectResult

    context(tx: NativeTransaction)
    override fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: MemorySegment,
        flags: Int,
    ) = onSendmsg?.invoke(sockfd, msg, flags) ?: sendmsgResult

    context(tx: NativeTransaction)
    override fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: MemorySegment,
        flags: Int,
    ) = onRecvmsg?.invoke(sockfd, msg, flags) ?: recvmsgResult

    context(tx: NativeTransaction)
    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ) = recvResult
}

public open class MockNativeProcess : NativeProcess {
    public var tid: io.mazewall.core.Tid = io.mazewall.core.Tid(1234)
    public var prctlResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var lastPrctlCommand: io.mazewall.core.PrctlCommand? = null

    override fun gettid() = tid

    context(tx: NativeTransaction)
    override fun prctl(
        command: io.mazewall.core.PrctlCommand,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> {
        lastPrctlCommand = command
        return prctlResult
    }
}

public open class MockNativeMemory : NativeMemory {
    public var processVmReadvResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var processVmWritevResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var readResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
    public var writeResult: SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)

    public var onProcessVmReadv: ((pid: io.mazewall.core.Pid, localIov: MemorySegment, liovcnt: Long, remoteIov: MemorySegment, riovcnt: Long, flags: Long) -> SyscallResult<Long, SyscallHandledState.Unhandled>)? = null

    context(tx: NativeTransaction)
    override fun processVmReadv(
        pid: io.mazewall.core.Pid,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ) = onProcessVmReadv?.invoke(pid, localIov, liovcnt, remoteIov, riovcnt, flags) ?: processVmReadvResult

    context(tx: NativeTransaction)
    override fun processVmWritev(
        pid: io.mazewall.core.Pid,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ) = processVmWritevResult

    context(tx: NativeTransaction)
    override fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ) = readResult

    context(tx: NativeTransaction)
    override fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ) = writeResult

    context(arena: Arena)
    override fun newSockFProg(
        filters: List<BpfInstruction>,
    ): MemorySegment {
        return arena.allocate(Layouts.SOCK_FPROG)
    }
}
