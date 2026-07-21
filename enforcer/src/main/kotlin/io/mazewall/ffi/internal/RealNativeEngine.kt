package io.mazewall.ffi.internal

import io.mazewall.*
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.LayoutValidator
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import io.mazewall.seccomp.BpfInstruction
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

/**
 * Real implementation of NativeEngine using FFM to call Linux system calls.
 */
@Suppress("TooManyFunctions")
internal object RealNativeEngine : NativeEngine, RawSyscallOperations {
    override val fileSystem: NativeFileSystem = RealNativeFileSystem
    override val networking: NativeNetworking = RealNativeNetworking
    override val process: NativeProcess = RealNativeProcess
    override val memory: NativeMemory = RealNativeMemory
    override val raw: RawSyscallOperations get() = this

    override fun <T> withTransaction(block: NativeTransaction.() -> T): T {
        return RealTransactionManager.withTransaction(block)
    }

    private val SYSCALL: MethodHandle =
        RealNativeHelper.downcall(
            "syscall",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val IOCTL_ADDR: MethodHandle =
        RealNativeHelper.downcall(
            "ioctl",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val IOCTL_LONG: MethodHandle =
        RealNativeHelper.downcall(
            "ioctl",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val FCNTL: MethodHandle =
        RealNativeHelper.downcall(
            "fcntl",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val POLL: MethodHandle =
        RealNativeHelper.downcall(
            "poll",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
            ),
            Linker.Option.captureCallState("errno"),
        )

    init {
        LayoutValidator.validate()
    }

    context(_: NativeTransaction)
    override fun syscall(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
        a5: io.mazewall.core.NativeArg,
        a6: io.mazewall.core.NativeArg,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret =
            SYSCALL.invokeExact(
                capturedState.segment,
                nr,
                a1.asLong,
                a2.asLong,
                a3.asLong,
                a4.asLong,
                a5.asLong,
                a6.asLong,
            ) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        syscall(nr, a1, a2, a3, a4, io.mazewall.core.NativeArg.LongArg(0L), io.mazewall.core.NativeArg.LongArg(0L))

    context(_: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: ManagedSegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = IOCTL_ADDR.invokeExact(capturedState.segment, fd.value, request, arg.native) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = IOCTL_LONG.invokeExact(capturedState.segment, fd.value, request, arg) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = FCNTL.invokeExact(capturedState.segment, fd.value, cmd, arg) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun poll(
        fds: ManagedSegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = POLL.invokeExact(capturedState.segment, fds.native, nfds, timeout) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }
}

internal object RealNativeFileSystem : NativeFileSystem {
    private val OPEN: MethodHandle =
        RealNativeHelper.downcall(
            "open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            Linker.Option.captureCallState("errno"),
        )
    private val CLOSE: MethodHandle =
        RealNativeHelper.downcall(
            "close",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.captureCallState("errno"),
        )
    private val READLINK: MethodHandle =
        RealNativeHelper.downcall(
            "readlink",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val MMAP: MethodHandle =
        RealNativeHelper.downcall(
            "mmap",
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )

    private val OPENAT: MethodHandle =
        RealNativeHelper.downcall(
            "openat",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            Linker.Option.captureCallState("errno"),
        )

    context(_: NativeTransaction)
    override fun open(
        path: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = OPEN.invokeExact(capturedState.segment, path.native, flags) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun openat(
        dirfd: Int,
        path: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = OPENAT.invokeExact(capturedState.segment, dirfd, path.native, flags) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun mmap(
        addr: Long,
        length: Long,
        prot: Int,
        flags: Int,
        fd: Int,
        offset: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret =
            MMAP.invokeExact(
                capturedState.segment,
                MemorySegment.ofAddress(addr),
                length,
                prot,
                flags,
                fd,
                offset,
            ) as MemorySegment
        return RealNativeHelper.result(ret.address(), capturedState.getErrno())
    }

    override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = CLOSE.invokeExact(capturedState.segment, fd.value) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun readlink(
        path: ManagedSegment,
        buf: ManagedSegment,
        bufsiz: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = READLINK.invokeExact(capturedState.segment, path.native, buf.native, bufsiz) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }
}

internal object RealNativeNetworking : NativeNetworking {
    private val SOCKETPAIR: MethodHandle =
        RealNativeHelper.downcall(
            "socketpair",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val SOCKET: MethodHandle =
        RealNativeHelper.downcall(
            "socket",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val BIND: MethodHandle =
        RealNativeHelper.downcall(
            "bind",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val LISTEN: MethodHandle =
        RealNativeHelper.downcall(
            "listen",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.captureCallState("errno"),
        )
    private val ACCEPT: MethodHandle =
        RealNativeHelper.downcall(
            "accept",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            Linker.Option.captureCallState("errno"),
        )
    private val ACCEPT4: MethodHandle =
        RealNativeHelper.downcall(
            "accept4",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val CONNECT: MethodHandle =
        RealNativeHelper.downcall(
            "connect",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val SENDMSG: MethodHandle =
        RealNativeHelper.downcall(
            "sendmsg",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val RECVMSG: MethodHandle =
        RealNativeHelper.downcall(
            "recvmsg",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val RECV: MethodHandle =
        RealNativeHelper.downcall(
            "recv",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
            ),
            Linker.Option.captureCallState("errno"),
        )

    context(_: NativeTransaction)
    override fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: ManagedSegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = SOCKETPAIR.invokeExact(capturedState.segment, domain, type, protocol, sv.native) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun accept4(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = ACCEPT4.invokeExact(capturedState.segment, sockfd.value, addr.native, addrlen.native, flags) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = SOCKET.invokeExact(capturedState.segment, domain, type, protocol) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun bind(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = BIND.invokeExact(capturedState.segment, sockfd.value, addr.native, addrlen) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun listen(
        sockfd: FileDescriptor<*, FdState.Open>,
        backlog: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = LISTEN.invokeExact(capturedState.segment, sockfd.value, backlog) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun accept(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = ACCEPT.invokeExact(capturedState.segment, sockfd.value, addr.native, addrlen.native) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun connect(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = CONNECT.invokeExact(capturedState.segment, sockfd.value, addr.native, addrlen) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = SENDMSG.invokeExact(capturedState.segment, sockfd.value, msg.native, flags) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = RECVMSG.invokeExact(capturedState.segment, sockfd.value, msg.native, flags) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = RECV.invokeExact(capturedState.segment, sockfd.value, buf.native, len, flags) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }
}

internal object RealNativeProcess : NativeProcess {
    private val PRCTL: MethodHandle =
        RealNativeHelper.downcall(
            "prctl",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val GETTID: MethodHandle =
        RealNativeHelper.downcall(
            "gettid",
            FunctionDescriptor.of(ValueLayout.JAVA_INT),
            Linker.Option.captureCallState("errno"),
        )
    private val PIDFD_OPEN: MethodHandle =
        RealNativeHelper.downcall(
            "pidfd_open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.captureCallState("errno"),
        )
    private val PIDFD_GETFD: MethodHandle =
        RealNativeHelper.downcall(
            "pidfd_getfd",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.captureCallState("errno"),
        )

    override fun gettid(): io.mazewall.core.Tid {
        val capturedState = ErrnoSegment.getThreadLocal()
        return io.mazewall.core.Tid(GETTID.invokeExact(capturedState.segment) as Int)
    }

    context(_: NativeTransaction)
    override fun prctl(command: io.mazewall.core.PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret =
            PRCTL.invokeExact(
                capturedState.segment,
                command.option,
                command.arg2.asLong,
                command.arg3.asLong,
                command.arg4.asLong,
                command.arg5.asLong,
            ) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun pidfdOpen(
        pid: Int,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = PIDFD_OPEN.invokeExact(capturedState.segment, pid, flags) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun pidfdGetFd(
        pidfd: Int,
        targetFd: Int,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = PIDFD_GETFD.invokeExact(capturedState.segment, pidfd, targetFd, flags) as Int
        return RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }
}

internal object RealNativeMemory : NativeMemory {
    private val PROCESS_VM_READV: MethodHandle =
        RealNativeHelper.downcall(
            "process_vm_readv",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val PROCESS_VM_WRITEV: MethodHandle =
        RealNativeHelper.downcall(
            "process_vm_writev",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val READ: MethodHandle =
        RealNativeHelper.downcall(
            "read",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )
    private val WRITE: MethodHandle =
        RealNativeHelper.downcall(
            "write",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
            Linker.Option.captureCallState("errno"),
        )

    context(_: NativeTransaction)
    override fun processVmReadv(
        pid: io.mazewall.core.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret =
            PROCESS_VM_READV.invokeExact(
                capturedState.segment,
                pid.value,
                localIov.native,
                liovcnt,
                remoteIov.native,
                riovcnt,
                flags,
            ) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun processVmWritev(
        pid: io.mazewall.core.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret =
            PROCESS_VM_WRITEV.invokeExact(
                capturedState.segment,
                pid.value,
                localIov.native,
                liovcnt,
                remoteIov.native,
                riovcnt,
                flags,
            ) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = READ.invokeExact(capturedState.segment, fd.value, buf.native, count) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        val capturedState = ErrnoSegment.getThreadLocal()
        val ret = WRITE.invokeExact(capturedState.segment, fd.value, buf.native, count) as Long
        return RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(arena: NativeArena)
    override fun newSockFProg(
        filters: List<BpfInstruction>,
    ): ManagedSegment {
        require(filters.size <= NativeConstants.BPF_MAXINSNS) {
            "BPF program exceeds kernel maximum instruction limit of ${NativeConstants.BPF_MAXINSNS} instructions"
        }
        val rawArena = arena.arena
        val filterArraySeg = with(rawArena) { SockFilterSegment.allocateArray(filters.size) }
        for (i in filters.indices) {
            val f = filters[i]
            val segment = SockFilterSegment(filterArraySeg.asSlice(i * Layouts.SOCK_FILTER_SIZE, Layouts.SOCK_FILTER_SIZE))
            segment.setCode(f.code)
            segment.setJt(f.jt.toByte())
            segment.setJf(f.jf.toByte())
            segment.setK(f.k)
        }
        val prog = with(rawArena) { SockFprogSegment.allocate() }
        prog.setLen(filters.size.toShort())
        prog.setFilter(filterArraySeg)
        return ConfinedSegment(prog.segment)
    }
}

internal object RealNativeHelper {
    private val linker: Linker = Linker.nativeLinker()
    private val stdlib: SymbolLookup = linker.defaultLookup()


    fun result(ret: Long, errno: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        if (ret < 0) LinuxNative.SyscallResult.Error(errno, ret)
        else LinuxNative.SyscallResult.Success(ret)

    fun downcall(
        name: String,
        fd: FunctionDescriptor,
        vararg options: Linker.Option,
    ): MethodHandle {
        val symbol =
            stdlib.find(name).orElse(null) ?: run {
                val ex = UnsupportedOperationException("Symbol $name not found in libc")
                val throwingHandle =
                    MethodHandles.insertArguments(
                        MethodHandles.throwException(Int::class.java, UnsupportedOperationException::class.java),
                        0,
                        ex,
                    )
                return MethodHandles.dropArguments(
                    throwingHandle,
                    0,
                    *fd.argumentLayouts().map { it.javaType() }.toTypedArray(),
                )
            }
        return linker.downcallHandle(symbol, fd, *options)
    }

    private fun MemoryLayout.javaType(): Class<*> =
        when (this) {
            is ValueLayout.OfByte -> Byte::class.java
            is ValueLayout.OfShort -> Short::class.java
            is ValueLayout.OfInt -> Int::class.java
            is ValueLayout.OfLong -> Long::class.java
            is ValueLayout.OfFloat -> Float::class.java
            is ValueLayout.OfDouble -> Double::class.java
            else -> MemorySegment::class.java
        }
}
