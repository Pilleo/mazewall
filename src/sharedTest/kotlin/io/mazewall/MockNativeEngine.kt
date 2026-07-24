package io.mazewall

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.seccomp.BpfInstruction

/**
 * A mock implementation of [NativeEngine] for testing fault injection.
 *
 * DESIGN INVARIANT: Mock allocations are aligned with the decoupled, zero-allocation design
 * of the core interfaces. Memory signatures use [ManagedSegment]s explicitly, allowing
 * clean mocking without under-the-hood arena creation.
 */
public open class MockNativeEngine(
    override val fileSystem: MockNativeFileSystem = MockNativeFileSystem(),
    override val networking: MockNativeNetworking = MockNativeNetworking(),
    override val process: MockNativeProcess = MockNativeProcess(),
    override val memory: MockNativeMemory = MockNativeMemory(),
) : NativeEngine, RawSyscallOperations {
    override val raw: RawSyscallOperations get() = this

    public var syscallResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var ioctlResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var fcntlResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var pollResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)

    public var onSyscall: (nr: Long, a1: io.mazewall.core.NativeArg, a2: io.mazewall.core.NativeArg, a3: io.mazewall.core.NativeArg, a4: io.mazewall.core.NativeArg, a5: io.mazewall.core.NativeArg, a6: io.mazewall.core.NativeArg) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _, _, _, _ -> syscallResult }
    public var onIoctl: (fd: FileDescriptor<*, FdState.Open>, request: Long, arg: ManagedSegment) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> ioctlResult }
    public var onPoll: (fds: ManagedSegment, nfds: Long, timeout: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> pollResult }

    override fun syscall(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
        a5: io.mazewall.core.NativeArg,
        a6: io.mazewall.core.NativeArg,
    ) = onSyscall(nr, a1, a2, a3, a4, a5, a6)

    override fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ) = onSyscall(nr, a1, a2, a3, a4, io.mazewall.core.NativeArg.LongArg(0L), io.mazewall.core.NativeArg.LongArg(0L))

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: ManagedSegment,
    ) = onIoctl(fd, request, arg)

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ) = onIoctl(fd, request, ManagedSegment.NULL) // Simplified for long args

    override fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ) = fcntlResult

    override fun poll(
        fds: ManagedSegment,
        nfds: Long,
        timeout: Int,
    ) = onPoll(fds, nfds, timeout)
}

public open class MockNativeFileSystem : NativeFileSystem {
    public var openResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var readlinkResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var closeResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var mmapResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)

    public var onOpen: (path: ManagedSegment, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _ -> openResult }
    public var onOpenat: (dirfd: Int, path: ManagedSegment, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> openResult }
    public var onClose: (fd: FileDescriptor<*, FdState.Open>) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { closeResult }

    override fun open(
        path: ManagedSegment,
        flags: Int,
    ) = onOpen(path, flags)

    override fun openat(
        dirfd: Int,
        path: ManagedSegment,
        flags: Int,
    ) = onOpenat(dirfd, path, flags)

    override fun readlink(
        path: ManagedSegment,
        buf: ManagedSegment,
        bufsiz: Long,
    ) = readlinkResult

    override fun mmap(
        addr: Long,
        length: Long,
        prot: Int,
        flags: Int,
        fd: Int,
        offset: Long,
    ) = mmapResult

    override fun close(fd: FileDescriptor<*, FdState.Open>) = onClose(fd)
}

public open class MockNativeNetworking : NativeNetworking {
    public var socketpairResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var socketResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var bindResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var listenResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var acceptResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var connectResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var sendmsgResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var recvmsgResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var recvResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)

    public var onSocket: (domain: Int, type: Int, protocol: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> socketResult }
    public var onConnect: (sockfd: FileDescriptor<*, FdState.Open>, addr: ManagedSegment, addrlen: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> connectResult }
    public var onAccept: (sockfd: FileDescriptor<*, FdState.Open>, addr: ManagedSegment, addrlen: ManagedSegment) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> acceptResult }
    public var onAccept4: (sockfd: FileDescriptor<*, FdState.Open>, addr: ManagedSegment, addrlen: ManagedSegment, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> acceptResult }
    public var onBind: (sockfd: FileDescriptor<*, FdState.Open>, addr: ManagedSegment, addrlen: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> bindResult }
    public var onListen: (sockfd: FileDescriptor<*, FdState.Open>, backlog: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _ -> listenResult }

    override fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: ManagedSegment,
    ) = socketpairResult

    override fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ) = onSocket(domain, type, protocol)

    override fun bind(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: Int,
    ) = onBind(sockfd, addr, addrlen)

    override fun listen(
        sockfd: FileDescriptor<*, FdState.Open>,
        backlog: Int,
    ) = onListen(sockfd, backlog)

    override fun accept(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
    ) = onAccept(sockfd, addr, addrlen)

    override fun accept4(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
        flags: Int,
    ) = onAccept4(sockfd, addr, addrlen, flags)

    override fun connect(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: Int,
    ) = onConnect(sockfd, addr, addrlen)

    override fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ) = sendmsgResult

    override fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ) = recvmsgResult

    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        len: Long,
        flags: Int,
    ) = recvResult
}

public open class MockNativeProcess : NativeProcess {
    public var tid: io.mazewall.core.Tid = io.mazewall.core.Tid(1234)
    public var prctlResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var lastPrctlCommand: io.mazewall.core.PrctlCommand? = null

    public var onPrctl: (command: io.mazewall.core.PrctlCommand) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { command ->
        lastPrctlCommand = command
        prctlResult
    }
    public var onPidfdOpen: (pid: Int, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _ -> LinuxNative.SyscallResult.Success(0L) }
    public var onPidfdGetFd: (pidfd: Int, targetFd: Int, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> LinuxNative.SyscallResult.Success(0L) }

    override fun gettid() = tid

    override fun prctl(
        command: io.mazewall.core.PrctlCommand,
    ) = onPrctl(command)

    override fun pidfdOpen(
        pid: Int,
        flags: Int,
    ) = onPidfdOpen(pid, flags)

    override fun pidfdGetFd(
        pidfd: Int,
        targetFd: Int,
        flags: Int,
    ) = onPidfdGetFd(pidfd, targetFd, flags)
}

public open class MockNativeMemory : NativeMemory {
    public var processVmReadvResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var processVmWritevResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var readResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var writeResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)

    public var onRead: (fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> readResult }
    public var onWrite: (fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> writeResult }

    override fun processVmReadv(
        pid: io.mazewall.core.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ) = processVmReadvResult

    override fun processVmWritev(
        pid: io.mazewall.core.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ) = processVmWritevResult

    override fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ) = onRead(fd, buf, count)

    override fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ) = onWrite(fd, buf, count)

    context(arena: NativeArena)
    override fun newSockFProg(
        filters: List<BpfInstruction>,
    ): ManagedSegment {
        require(filters.size <= NativeConstants.BPF_MAXINSNS) {
            "BPF program exceeds kernel maximum instruction limit"
        }
        return arena.allocate(Layouts.SOCK_FPROG)
    }
}
