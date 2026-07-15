package io.mazewall

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
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
) : NativeEngine, RawSyscallOperations {
    override val raw: RawSyscallOperations get() = this

    public var onWithTransaction: ((NativeTransaction.() -> Any?) -> Any?)? = null

    @Suppress("UNCHECKED_CAST")
    override fun <T> withTransaction(block: NativeTransaction.() -> T): T {
        return onWithTransaction?.invoke(block as NativeTransaction.() -> Any?) as T
            ?: RealTransactionManager.withTransaction(block)
    }

    public var syscallResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var ioctlResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var fcntlResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var pollResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)

    public var onSyscall: (context: NativeTransaction, nr: Long, a1: io.mazewall.core.NativeArg, a2: io.mazewall.core.NativeArg, a3: io.mazewall.core.NativeArg, a4: io.mazewall.core.NativeArg, a5: io.mazewall.core.NativeArg, a6: io.mazewall.core.NativeArg) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _, _, _, _, _ -> syscallResult }
    public var onIoctl: (context: NativeTransaction, fd: FileDescriptor<*, FdState.Open>, request: Long, arg: MemorySegment) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> ioctlResult }
    public var onPoll: (context: NativeTransaction, fds: MemorySegment, nfds: Long, timeout: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> pollResult }

    context(context: NativeTransaction)
    override fun syscall(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
        a5: io.mazewall.core.NativeArg,
        a6: io.mazewall.core.NativeArg,
    ) = onSyscall(context, nr, a1, a2, a3, a4, a5, a6)

    context(context: NativeTransaction)
    override fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ) = onSyscall(context, nr, a1, a2, a3, a4, io.mazewall.core.NativeArg.LongArg(0L), io.mazewall.core.NativeArg.LongArg(0L))

    context(context: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: MemorySegment,
    ) = onIoctl(context, fd, request, arg)

    context(context: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ) = onIoctl(context, fd, request, MemorySegment.NULL) // Simplified for long args

    context(_: NativeTransaction)
    override fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ) = fcntlResult

    context(context: NativeTransaction)
    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ) = onPoll(context, fds, nfds, timeout)
}

public open class MockNativeFileSystem : NativeFileSystem {
    public var openResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var readlinkResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var closeResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var mmapResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)

    public var onOpen: (context: NativeTransaction, path: MemorySegment, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> openResult }
    public var onOpenat: (context: NativeTransaction, dirfd: Int, path: MemorySegment, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> openResult }
    public var onClose: (fd: FileDescriptor<*, FdState.Open>) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { closeResult }

    context(context: NativeTransaction)
    override fun open(
        path: MemorySegment,
        flags: Int,
    ) = onOpen(context, path, flags)

    context(context: NativeTransaction)
    override fun openat(
        dirfd: Int,
        path: MemorySegment,
        flags: Int,
    ) = onOpenat(context, dirfd, path, flags)

    context(_: NativeTransaction)
    override fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ) = readlinkResult

    context(_: NativeTransaction)
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

    public var onSocket: (context: NativeTransaction, domain: Int, type: Int, protocol: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> socketResult }
    public var onConnect: (context: NativeTransaction, sockfd: FileDescriptor<*, FdState.Open>, addr: MemorySegment, addrlen: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> connectResult }
    public var onAccept: (context: NativeTransaction, sockfd: FileDescriptor<*, FdState.Open>, addr: MemorySegment, addrlen: MemorySegment) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> acceptResult }
    public var onAccept4: (context: NativeTransaction, sockfd: FileDescriptor<*, FdState.Open>, addr: MemorySegment, addrlen: MemorySegment, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _, _ -> acceptResult }
    public var onBind: (context: NativeTransaction, sockfd: FileDescriptor<*, FdState.Open>, addr: MemorySegment, addrlen: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> bindResult }
    public var onListen: (context: NativeTransaction, sockfd: FileDescriptor<*, FdState.Open>, backlog: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> listenResult }

    context(_: NativeTransaction)
    override fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ) = socketpairResult

    context(context: NativeTransaction)
    override fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ) = onSocket(context, domain, type, protocol)

    context(context: NativeTransaction)
    override fun bind(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: Int,
    ) = onBind(context, sockfd, addr, addrlen)

    context(context: NativeTransaction)
    override fun listen(
        sockfd: FileDescriptor<*, FdState.Open>,
        backlog: Int,
    ) = onListen(context, sockfd, backlog)

    context(context: NativeTransaction)
    override fun accept(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ) = onAccept(context, sockfd, addr, addrlen)

    context(context: NativeTransaction)
    override fun accept4(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: MemorySegment,
        flags: Int,
    ) = onAccept4(context, sockfd, addr, addrlen, flags)

    context(context: NativeTransaction)
    override fun connect(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: Int,
    ) = onConnect(context, sockfd, addr, addrlen)

    context(_: NativeTransaction)
    override fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: MemorySegment,
        flags: Int,
    ) = sendmsgResult

    context(_: NativeTransaction)
    override fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: MemorySegment,
        flags: Int,
    ) = recvmsgResult

    context(_: NativeTransaction)
    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ) = recvResult
}

public open class MockNativeProcess : NativeProcess {
    public var tid: io.mazewall.core.Tid = io.mazewall.core.Tid(1234)
    public var prctlResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var lastPrctlCommand: io.mazewall.core.PrctlCommand? = null

    public var onPrctl: (context: NativeTransaction, command: io.mazewall.core.PrctlCommand) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, command ->
        lastPrctlCommand = command
        prctlResult
    }
    public var onPidfdOpen: (context: NativeTransaction, pid: Int, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> LinuxNative.SyscallResult.Success(0L) }
    public var onPidfdGetFd: (context: NativeTransaction, pidfd: Int, targetFd: Int, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> LinuxNative.SyscallResult.Success(0L) }

    override fun gettid() = tid

    context(context: NativeTransaction)
    override fun prctl(
        command: io.mazewall.core.PrctlCommand,
    ) = onPrctl(context, command)

    context(context: NativeTransaction)
    override fun pidfdOpen(
        pid: Int,
        flags: Int,
    ) = onPidfdOpen(context, pid, flags)

    context(context: NativeTransaction)
    override fun pidfdGetFd(
        pidfd: Int,
        targetFd: Int,
        flags: Int,
    ) = onPidfdGetFd(context, pidfd, targetFd, flags)
}

public open class MockNativeMemory : NativeMemory {
    public var processVmReadvResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var processVmWritevResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var readResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var writeResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)

    public var onRead: (context: NativeTransaction, fd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, count: Long) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> readResult }
    public var onWrite: (context: NativeTransaction, fd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, count: Long) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _, _ -> writeResult }

    context(_: NativeTransaction)
    override fun processVmReadv(
        pid: io.mazewall.core.Pid,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ) = processVmReadvResult

    context(_: NativeTransaction)
    override fun processVmWritev(
        pid: io.mazewall.core.Pid,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ) = processVmWritevResult

    context(context: NativeTransaction)
    override fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ) = onRead(context, fd, buf, count)

    context(context: NativeTransaction)
    override fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ) = onWrite(context, fd, buf, count)

    context(arena: Arena)
    override fun newSockFProg(
        filters: List<BpfInstruction>,
    ): MemorySegment {
        return arena.allocate(Layouts.SOCK_FPROG)
    }
}
