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
 *
 * DESIGN INVARIANT: This implementation is completely pure and allocation-free on the per-syscall
 * hot paths. It does not utilize [nativeScope] or create internal [Arena] instances.
 * Captured error states are optimized using thread-local segments via [ErrnoSegment].
 */
@Suppress("TooManyFunctions")
internal object RealNativeEngine : NativeEngine, RawSyscallOperations {
    override val fileSystem: NativeFileSystem = RealNativeFileSystem
    override val networking: NativeNetworking = RealNativeNetworking
    override val process: NativeProcess = RealNativeProcess
    override val memory: NativeMemory = RealNativeMemory
    override val raw: RawSyscallOperations get() = this

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

    override fun syscall(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
        a5: io.mazewall.core.NativeArg,
        a6: io.mazewall.core.NativeArg,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.syscall(
            SYSCALL,
            nr,
            a1.asLong,
            a2.asLong,
            a3.asLong,
            a4.asLong,
            a5.asLong,
            a6.asLong,
        )
    }

    override fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        syscall(nr, a1, a2, a3, a4, io.mazewall.core.NativeArg.LongArg(0L), io.mazewall.core.NativeArg.LongArg(0L))

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: ManagedSegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.ioctlAddr(IOCTL_ADDR, fd.value, request, arg.native)
    }

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.ioctlLong(IOCTL_LONG, fd.value, request, arg)
    }

    override fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.fcntl(FCNTL, fd.value, cmd, arg)
    }

    override fun poll(
        fds: ManagedSegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.poll(POLL, fds.native, nfds, timeout)
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

    override fun open(
        path: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.open(OPEN, path.native, flags)
    }

    override fun openat(
        dirfd: Int,
        path: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.openat(OPENAT, dirfd, path.native, flags)
    }

    override fun mmap(
        addr: Long,
        length: Long,
        prot: Int,
        flags: Int,
        fd: Int,
        offset: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.mmap(MMAP, MemorySegment.ofAddress(addr), length, prot, flags, fd, offset)
    }

    override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.close(CLOSE, fd.value)
    }

    override fun readlink(
        path: ManagedSegment,
        buf: ManagedSegment,
        bufsiz: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.readlink(READLINK, path.native, buf.native, bufsiz)
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

    override fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: ManagedSegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.socketpair(SOCKETPAIR, domain, type, protocol, sv.native)
    }

    override fun accept4(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.accept4(ACCEPT4, sockfd.value, addr.native, addrlen.native, flags)
    }

    override fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.socket(SOCKET, domain, type, protocol)
    }

    override fun bind(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.bind(BIND, sockfd.value, addr.native, addrlen)
    }

    override fun listen(
        sockfd: FileDescriptor<*, FdState.Open>,
        backlog: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.listen(LISTEN, sockfd.value, backlog)
    }

    override fun accept(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: ManagedSegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.accept(ACCEPT, sockfd.value, addr.native, addrlen.native)
    }

    override fun connect(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: ManagedSegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.connect(CONNECT, sockfd.value, addr.native, addrlen)
    }

    override fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.sendmsg(SENDMSG, sockfd.value, msg.native, flags)
    }

    override fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: ManagedSegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.recvmsg(RECVMSG, sockfd.value, msg.native, flags)
    }

    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.recv(RECV, sockfd.value, buf.native, len, flags)
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
        return SyscallInvoker.gettid(GETTID)
    }

    override fun prctl(command: io.mazewall.core.PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.prctl(
            PRCTL,
            command.option,
            command.arg2.asLong,
            command.arg3.asLong,
            command.arg4.asLong,
            command.arg5.asLong,
        )
    }

    override fun pidfdOpen(
        pid: Int,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.pidfdOpen(PIDFD_OPEN, pid, flags)
    }

    override fun pidfdGetFd(
        pidfd: Int,
        targetFd: Int,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.pidfdGetFd(PIDFD_GETFD, pidfd, targetFd, flags)
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

    override fun processVmReadv(
        pid: io.mazewall.core.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.processVmReadv(
            PROCESS_VM_READV,
            pid.value,
            localIov.native,
            liovcnt,
            remoteIov.native,
            riovcnt,
            flags,
        )
    }

    override fun processVmWritev(
        pid: io.mazewall.core.Pid,
        localIov: ManagedSegment,
        liovcnt: Long,
        remoteIov: ManagedSegment,
        riovcnt: Long,
        flags: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        return SyscallInvoker.processVmWritev(
            PROCESS_VM_WRITEV,
            pid.value,
            localIov.native,
            liovcnt,
            remoteIov.native,
            riovcnt,
            flags,
        )
    }

    override fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.read(READ, fd.value, buf.native, count)
    }

    override fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        require(fd.isValid) { "FileDescriptor is invalid or closed" }
        return SyscallInvoker.write(WRITE, fd.value, buf.native, count)
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
