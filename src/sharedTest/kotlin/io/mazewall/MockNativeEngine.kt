package io.mazewall

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.claimDupIfNeeded
import io.mazewall.core.ebadfIfRetiredPollfds
import io.mazewall.core.ebadfUnlessDirfd
import io.mazewall.core.ebadfUnlessLive
import io.mazewall.core.ebadfUnlessMmapBacking
import io.mazewall.ffi.IoctlCommand
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
    ) = ebadfUnlessLive(a1, a2, a3, a4, a5, a6) ?: onSyscall(nr, a1, a2, a3, a4, a5, a6)

    override fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ) = ebadfUnlessLive(a1, a2, a3, a4)
        ?: onSyscall(nr, a1, a2, a3, a4, io.mazewall.core.NativeArg.LongArg(0L), io.mazewall.core.NativeArg.LongArg(0L))

    override fun <Req, Res> ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        command: IoctlCommand<Req, Res>,
        arg: Req,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val segment = when (arg) {
            is io.mazewall.ffi.TypedSegment<*> -> arg.segment
            is ManagedSegment -> arg
            else -> throw IllegalArgumentException("Unsupported ioctl argument type")
        }
        return raw.ioctl(fd, command.code, segment)
    }

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: ManagedSegment,
    ) = fd.ebadfUnlessLive() ?: onIoctl(fd, request, arg)

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ) = fd.ebadfUnlessLive() ?: onIoctl(fd, request, ManagedSegment.NULL) // Simplified for long args

    override fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ) = fd.ebadfUnlessLive() ?: fcntlResult.claimDupIfNeeded(cmd)

    override fun poll(
        fds: ManagedSegment,
        nfds: Long,
        timeout: Int,
    ) = ebadfIfRetiredPollfds(fds, nfds) ?: onPoll(fds, nfds, timeout)
}

public open class MockNativeFileSystem : NativeFileSystem {
    public var openResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100L)
    public var readlinkResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var closeResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
    public var mmapResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)

    public var onOpen: (path: ManagedSegment, flags: io.mazewall.core.OpenFlags) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _ -> openResult }
    public var onOpenat: (dirfd: FileDescriptor<*, FdState.Open>, path: ManagedSegment, flags: io.mazewall.core.OpenFlags) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> openResult }
    public var onClose: (fd: FileDescriptor<*, FdState.Open>) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { closeResult }

    override fun open(
        path: ManagedSegment,
        flags: io.mazewall.core.OpenFlags,
    ) = onOpen(path, flags)

    override fun openat(
        dirfd: FileDescriptor<*, FdState.Open>,
        path: ManagedSegment,
        flags: io.mazewall.core.OpenFlags,
    ) = dirfd.ebadfUnlessDirfd() ?: onOpenat(dirfd, path, flags)

    override fun readlink(
        path: ManagedSegment,
        buf: ManagedSegment,
        bufsiz: Long,
    ) = readlinkResult

    override fun mmap(
        addr: Long,
        length: Long,
        prot: io.mazewall.core.MmapProt,
        flags: io.mazewall.core.MmapFlags,
        fd: FileDescriptor<*, FdState.Open>,
        offset: Long,
    ) = fd.ebadfUnlessMmapBacking() ?: mmapResult

    override fun close(fd: FileDescriptor<*, FdState.Open>) =
        fd.ebadfUnlessLive() ?: run {
            fd.retireForClose()
            onClose(fd)
        }
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
    ) = sockfd.ebadfUnlessLive() ?: onBind(sockfd, addr, addrlen)

    override fun listen(
        sockfd: FileDescriptor<*, FdState.Open>,
        backlog: Int,
    ) = sockfd.ebadfUnlessLive() ?: onListen(sockfd, backlog)

    override fun accept(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
    ) = sockfd.ebadfUnlessLive() ?: onAccept(sockfd, addr, addrlen)

    override fun accept4(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
        flags: Int,
    ) = sockfd.ebadfUnlessLive() ?: onAccept4(sockfd, addr, addrlen, flags)

    override fun connect(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: Int,
    ) = sockfd.ebadfUnlessLive() ?: onConnect(sockfd, addr, addrlen)

    override fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ) = sockfd.ebadfUnlessLive() ?: sendmsgResult

    override fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ) = sockfd.ebadfUnlessLive() ?: recvmsgResult

    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        len: Long,
        flags: Int,
    ) = sockfd.ebadfUnlessLive() ?: recvResult
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
    public var onPidfdGetFd: (pidfd: FileDescriptor<*, FdState.Open>, targetFd: Int, flags: Int) -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = { _, _, _ -> LinuxNative.SyscallResult.Success(0L) }

    public var archPrctlLongResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
    public var archPrctlAddrResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

    public var lastArchPrctlCode: Int? = null
    public var lastArchPrctlLong: Long? = null
    public var lastArchPrctlAddr: ManagedSegment? = null

    override fun gettid() = tid

    override fun prctl(
        command: io.mazewall.core.PrctlCommand,
    ) = onPrctl(command)

    override fun pidfdOpen(
        pid: Int,
        flags: Int,
    ) = onPidfdOpen(pid, flags)

    override fun pidfdGetFd(
        pidfd: FileDescriptor<*, FdState.Open>,
        targetFd: Int,
        flags: Int,
    ) = pidfd.ebadfUnlessLive() ?: onPidfdGetFd(pidfd, targetFd, flags)

    override fun archPrctl(code: Int, addr: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        lastArchPrctlCode = code
        lastArchPrctlLong = addr
        return archPrctlLongResult
    }

    override fun archPrctl(code: Int, addr: ManagedSegment): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        lastArchPrctlCode = code
        lastArchPrctlAddr = addr
        return archPrctlAddrResult
    }
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
    ) = fd.ebadfUnlessLive() ?: onRead(fd, buf, count)

    override fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ) = fd.ebadfUnlessLive() ?: onWrite(fd, buf, count)

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
