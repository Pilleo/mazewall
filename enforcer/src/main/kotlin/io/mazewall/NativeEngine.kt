package io.mazewall

import io.mazewall.LinuxNative.SyscallResult
import io.mazewall.LinuxNative.SyscallHandledState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FdState
import io.mazewall.seccomp.BpfInstruction
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * A capability token that proves the caller is operating within a sanctioned
 * native transaction scope. Required for raw system calls and other sensitive
 * native interactions.
 */
public interface NativeTransaction {
    public val arena: Arena
}

public interface TransactionManager {
    public fun <T> withTransaction(block: NativeTransaction.() -> T): T
}

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

    context(tx: NativeTransaction)
    fun syscall(
        nr: Long,
        a1: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a2: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a3: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a4: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a5: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a6: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: MemorySegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>
}

public interface NativeFileSystem {
    context(tx: NativeTransaction)
    fun open(
        path: MemorySegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun mmap(
        addr: Long,
        length: Long,
        prot: Int,
        flags: Int,
        fd: Int,
        offset: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    fun close(fd: FileDescriptor<*, FdState.Open>): SyscallResult<Long, SyscallHandledState.Unhandled>
}

public interface NativeNetworking {
    context(tx: NativeTransaction)
    fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun bind(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun listen(
        sockfd: FileDescriptor<*, FdState.Open>,
        backlog: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun accept(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun connect(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: MemorySegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: MemorySegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>
}

public interface NativeProcess {
    fun gettid(): io.mazewall.core.Tid

    context(tx: NativeTransaction)
    fun prctl(command: io.mazewall.core.PrctlCommand): SyscallResult<Long, SyscallHandledState.Unhandled>
}

public interface NativeMemory {
    context(tx: NativeTransaction)
    fun processVmReadv(
        pid: io.mazewall.core.Pid,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun processVmWritev(
        pid: io.mazewall.core.Pid,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(tx: NativeTransaction)
    fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(arena: Arena)
    fun newSockFProg(
        filters: List<BpfInstruction>,
    ): MemorySegment
}
