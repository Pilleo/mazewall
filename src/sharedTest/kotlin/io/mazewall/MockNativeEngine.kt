package io.mazewall

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
    public var syscallResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var ioctlResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var fcntlResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var pollResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)

    context(_: NativeTransaction)
    override fun syscall(
        nr: Long,
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
        a5: Any?,
        a6: Any?,
    ) = syscallResult

    context(_: NativeTransaction)
    override fun syscall4(
        nr: Long,
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
    ) = syscallResult

    context(_: NativeTransaction)
    override fun ioctl(
        fd: LinuxNative.FileDescriptor,
        request: Long,
        arg: MemorySegment,
    ) = ioctlResult

    context(_: NativeTransaction)
    override fun ioctl(
        fd: LinuxNative.FileDescriptor,
        request: Long,
        arg: Long,
    ) = ioctlResult

    context(_: NativeTransaction)
    override fun fcntl(
        fd: LinuxNative.FileDescriptor,
        cmd: Int,
        arg: Long,
    ) = fcntlResult

    context(_: NativeTransaction)
    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ) = pollResult
}

public open class MockNativeFileSystem : NativeFileSystem {
    public var openResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var readlinkResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var closeResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)

    context(_: NativeTransaction)
    override fun open(
        path: MemorySegment,
        flags: Int,
    ) = openResult

    context(_: NativeTransaction)
    override fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ) = readlinkResult

    override fun close(fd: LinuxNative.FileDescriptor) = closeResult
}

public open class MockNativeNetworking : NativeNetworking {
    public var socketpairResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var socketResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var bindResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var listenResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var acceptResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var connectResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var sendmsgResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var recvmsgResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var recvResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)

    context(_: NativeTransaction)
    override fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ) = socketpairResult

    context(_: NativeTransaction)
    override fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ) = socketResult

    context(_: NativeTransaction)
    override fun bind(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ) = bindResult

    context(_: NativeTransaction)
    override fun listen(
        sockfd: LinuxNative.FileDescriptor,
        backlog: Int,
    ) = listenResult

    context(_: NativeTransaction)
    override fun accept(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ) = acceptResult

    context(_: NativeTransaction)
    override fun connect(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ) = connectResult

    context(_: NativeTransaction)
    override fun sendmsg(
        sockfd: LinuxNative.FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ) = sendmsgResult

    context(_: NativeTransaction)
    override fun recvmsg(
        sockfd: LinuxNative.FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ) = recvmsgResult

    context(_: NativeTransaction)
    override fun recv(
        sockfd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ) = recvResult
}

public open class MockNativeProcess : NativeProcess {
    public var tid: io.mazewall.core.Pid = io.mazewall.core.Pid(1234)
    public var prctlResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)

    override fun gettid() = tid

    context(_: NativeTransaction)
    override fun prctl(
        option: Int,
        arg2: Any?,
        arg3: Any?,
        arg4: Any?,
        arg5: Any?,
    ) = prctlResult
}

public open class MockNativeMemory : NativeMemory {
    public var processVmReadvResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var readResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)
    public var writeResult: LinuxNative.SyscallResult<Long> = LinuxNative.SyscallResult.Success(0L)

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
    override fun read(
        fd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        count: Long,
    ) = readResult

    context(_: NativeTransaction)
    override fun write(
        fd: LinuxNative.FileDescriptor,
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
