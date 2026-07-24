package io.mazewall

import io.mazewall.LinuxNative.SyscallResult
import io.mazewall.LinuxNative.SyscallHandledState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FdState
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.seccomp.BpfInstruction

/**
 * A capability token that proves the caller is operating within a sanctioned
 * native transaction scope. Required for raw system calls and other sensitive
 * native interactions.
 */
public interface NativeTransaction

public interface TransactionManager {
    public fun <T> withTransaction(block: NativeTransaction.() -> T): T
}

/**
 * Interface for Linux native system calls and utility functions.
 * Decoupling this allows for mocking and fault injection in tests.
 *
 * DESIGN INVARIANT: This interface is decoupled from FFM implicit Arenas and
 * the [nativeScope] utility. To enable zero-allocation execution and clear memory
 * boundaries, methods requiring off-heap memory accept pre-allocated [ManagedSegment]
 * parameters exclusively. Callers are responsible for managing the lifecycles of their
 * own transient arenas (e.g., using iteration-level scopes in high-throughput reactor loops).
 *
 * All sensitive methods require a [NativeTransaction] capability in context.
 */
public interface NativeEngine {
    public val fileSystem: NativeFileSystem
    public val networking: NativeNetworking
    public val process: NativeProcess
    public val memory: NativeMemory
    public val raw: RawSyscallOperations

    /**
     * Executes the given [block] within a [NativeTransaction] context.
     * Raw system calls and sensitive native operations are only available within this scope.
     */
    public fun <T> withTransaction(block: NativeTransaction.() -> T): T
}

/**
 * Interface for raw Linux system calls and low-level I/O operations.
 * Use with caution: these methods bypass high-level domain abstractions and
 * are intended for use only by core enforcer logic or specialized low-level components.
 */
public interface RawSyscallOperations {
    context(_: NativeTransaction)
    fun syscall(
        nr: Long,
        a1: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a2: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a3: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a4: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a5: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
        a6: io.mazewall.core.NativeArg = io.mazewall.core.NativeArg.LongArg(0L),
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: ManagedSegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun poll(
        fds: ManagedSegment,
        nfds: Long,
        timeout: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>
}

public interface NativeFileSystem {
    context(_: NativeTransaction)
    fun open(
        path: ManagedSegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun openat(
        dirfd: Int,
        path: ManagedSegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun readlink(
        path: ManagedSegment,
        buf: ManagedSegment,
        bufsiz: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
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
    context(_: NativeTransaction)
    fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: ManagedSegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun bind(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun listen(
        sockfd: FileDescriptor<*, FdState.Open>,
        backlog: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun accept(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun accept4(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun connect(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        len: Long,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>
}

public interface NativeProcess {
    fun gettid(): io.mazewall.core.Tid

    context(_: NativeTransaction)
    fun prctl(command: io.mazewall.core.PrctlCommand): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun pidfdOpen(
        pid: Int,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun pidfdGetFd(
        pidfd: Int,
        targetFd: Int,
        flags: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>
}

public interface NativeMemory {
    context(_: NativeTransaction)
    fun processVmReadv(
        pid: io.mazewall.core.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun processVmWritev(
        pid: io.mazewall.core.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(_: NativeTransaction)
    fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled>

    context(arena: NativeArena)
    fun newSockFProg(
        filters: List<BpfInstruction>,
    ): ManagedSegment
}
