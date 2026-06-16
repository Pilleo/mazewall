package io.mazewall

import io.mazewall.core.FileDescriptor
import io.mazewall.seccomp.BpfInstruction
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * A capability token that proves the caller is operating within a sanctioned
 * native transaction scope. Required for raw system calls and other sensitive
 * native interactions.
 */
public interface NativeTransaction

/**
 * Interface for Linux native system calls and utility functions.
 * Decoupling this allows for mocking and fault injection in tests.
 *
 * All sensitive methods require a [NativeTransaction] capability in context.
 */
public interface NativeEngine {
    public val fileSystem: NativeFileSystem
    public val networking: NativeNetworking
    public val process: NativeProcess
    public val memory: NativeMemory

    context(_: NativeTransaction)
    fun syscall(
        nr: Long,
        a1: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a2: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a3: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a4: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a5: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a6: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun ioctl(
        fd: FileDescriptor<*>,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun ioctl(
        fd: FileDescriptor<*>,
        request: Long,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun fcntl(
        fd: FileDescriptor<*>,
        cmd: Int,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>
}

public interface NativeFileSystem {
    context(_: NativeTransaction)
    fun open(
        path: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    fun close(fd: FileDescriptor<*>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>
}

public interface NativeNetworking {
    context(_: NativeTransaction)
    fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun bind(
        sockfd: FileDescriptor<*>,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun listen(
        sockfd: FileDescriptor<*>,
        backlog: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun accept(
        sockfd: FileDescriptor<*>,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun connect(
        sockfd: FileDescriptor<*>,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun sendmsg(
        sockfd: FileDescriptor<*>,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun recvmsg(
        sockfd: FileDescriptor<*>,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun recv(
        sockfd: FileDescriptor<*>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>
}

public interface NativeProcess {
    fun gettid(): io.mazewall.core.Pid

    context(_: NativeTransaction)
    fun prctl(
        option: Int,
        arg2: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        arg3: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        arg4: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        arg5: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>
}

public interface NativeMemory {
    context(_: NativeTransaction)
    fun processVmReadv(
        pid: io.mazewall.core.Pid,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun read(
        fd: FileDescriptor<*>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun write(
        fd: FileDescriptor<*>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    context(arena: Arena)
    fun newSockFProg(
        filters: List<BpfInstruction>,
    ): MemorySegment
}
