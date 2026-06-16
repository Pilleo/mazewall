package io.mazewall

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
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun ioctl(
        fd: LinuxNative.FileDescriptor,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun ioctl(
        fd: LinuxNative.FileDescriptor,
        request: Long,
        arg: Long,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun fcntl(
        fd: LinuxNative.FileDescriptor,
        cmd: Int,
        arg: Long,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long>
}

public interface NativeFileSystem {
    context(_: NativeTransaction)
    fun open(
        path: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ): LinuxNative.SyscallResult<Long>

    fun close(fd: LinuxNative.FileDescriptor): LinuxNative.SyscallResult<Long>
}

public interface NativeNetworking {
    context(_: NativeTransaction)
    fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun bind(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun listen(
        sockfd: LinuxNative.FileDescriptor,
        backlog: Int,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun accept(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun connect(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun sendmsg(
        sockfd: LinuxNative.FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun recvmsg(
        sockfd: LinuxNative.FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun recv(
        sockfd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long>
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
    ): LinuxNative.SyscallResult<Long>
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
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun read(
        fd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long>

    context(_: NativeTransaction)
    fun write(
        fd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long>

    context(arena: Arena)
    fun newSockFProg(
        filters: List<BpfInstruction>,
    ): MemorySegment
}
