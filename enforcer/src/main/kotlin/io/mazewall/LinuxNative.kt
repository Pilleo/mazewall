package io.mazewall

import io.mazewall.ffi.LayoutValidator
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.ErrnoSegment
import io.mazewall.ffi.memory.SockFilterSegment
import io.mazewall.ffi.memory.SockFprogSegment
import io.mazewall.ffi.memory.nativeScope
import io.mazewall.seccomp.BpfInstruction
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

/**
 * Entry point for all native Linux interactions.
 *
 * This object manages the active [NativeEngine] and provides a capability-based
 * API for sensitive operations via [withTransaction].
 */
@Suppress("TooManyFunctions")
public object LinuxNative : NativeEngine {
    @Volatile
    private var engine: NativeEngine = RealNativeEngine

    /**
     * Swaps the active native engine. Used for testing and fault injection.
     */
    @Suppress("spotbugs:ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    fun setEngine(newEngine: NativeEngine) {
        engine = newEngine
    }

    /**
     * Restores the default RealNativeEngine.
     */
    @Suppress("spotbugs:ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    fun resetToDefault() {
        engine = RealNativeEngine
    }

    @JvmSynthetic
    @PublishedApi
    internal val TRANSACTION_INSTANCE: NativeTransaction = object : NativeTransaction {}

    /**
     * Executes the given [block] within a [NativeTransaction] context.
     * Raw system calls and sensitive native operations are only available within this scope.
     */
    public inline fun <T> withTransaction(block: context(NativeTransaction) () -> T): T {
        return block(TRANSACTION_INSTANCE)
    }

    fun getFileSystem(): NativeFileSystem = engine

    fun getNetworking(): NativeNetworking = engine

    fun getProcess(): NativeProcess = engine

    fun getMemory(): NativeMemory = engine

    context(_: NativeTransaction)
    override fun prctl(
        option: Int,
        arg2: Any?,
        arg3: Any?,
        arg4: Any?,
        arg5: Any?,
    ) = engine.prctl(option, arg2, arg3, arg4, arg5)

    context(_: NativeTransaction)
    override fun syscall(
        nr: Long,
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
        a5: Any?,
        a6: Any?,
    ) = engine.syscall(nr, a1, a2, a3, a4, a5, a6)

    context(_: NativeTransaction)
    override fun syscall4(
        nr: Long,
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
    ) = engine.syscall4(nr, a1, a2, a3, a4)

    context(_: NativeTransaction)
    override fun open(
        path: MemorySegment,
        flags: Int,
    ) = engine.open(path, flags)

    override fun close(fd: FileDescriptor) = engine.close(fd)

    context(_: NativeTransaction)
    override fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ) = engine.socketpair(domain, type, protocol, sv)

    context(_: NativeTransaction)
    override fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ) = engine.socket(domain, type, protocol)

    context(_: NativeTransaction)
    override fun bind(
        sockfd: FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ) = engine.bind(sockfd, addr, addrlen)

    context(_: NativeTransaction)
    override fun listen(
        sockfd: FileDescriptor,
        backlog: Int,
    ) = engine.listen(sockfd, backlog)

    context(_: NativeTransaction)
    override fun accept(
        sockfd: FileDescriptor,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ) = engine.accept(sockfd, addr, addrlen)

    context(_: NativeTransaction)
    override fun connect(
        sockfd: FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ) = engine.connect(sockfd, addr, addrlen)

    context(_: NativeTransaction)
    override fun sendmsg(
        sockfd: FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ) = engine.sendmsg(sockfd, msg, flags)

    context(_: NativeTransaction)
    override fun recvmsg(
        sockfd: FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ) = engine.recvmsg(sockfd, msg, flags)

    context(_: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor,
        request: Long,
        arg: MemorySegment,
    ) = engine.ioctl(fd, request, arg)

    context(_: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor,
        request: Long,
        arg: Long,
    ) = engine.ioctl(fd, request, arg)

    context(_: NativeTransaction)
    override fun processVmReadv(
        pid: Int,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ) = engine.processVmReadv(pid, localIov, liovcnt, remoteIov, riovcnt, flags)

    context(_: NativeTransaction)
    override fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ) = engine.readlink(path, buf, bufsiz)

    context(_: NativeTransaction)
    override fun read(
        fd: FileDescriptor,
        buf: MemorySegment,
        count: Long,
    ) = engine.read(fd, buf, count)

    context(_: NativeTransaction)
    override fun write(
        fd: FileDescriptor,
        buf: MemorySegment,
        count: Long,
    ) = engine.write(fd, buf, count)

    context(_: NativeTransaction)
    override fun recv(
        sockfd: FileDescriptor,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ) = engine.recv(sockfd, buf, len, flags)

    context(_: NativeTransaction)
    override fun fcntl(
        fd: FileDescriptor,
        cmd: Int,
        arg: Long,
    ) = engine.fcntl(fd, cmd, arg)

    override fun gettid() = engine.gettid()

    context(_: NativeTransaction)
    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ) = engine.poll(fds, nfds, timeout)

    context(arena: Arena)
    override fun newSockFProg(
        filters: List<BpfInstruction>,
    ) = with(arena) { engine.newSockFProg(filters) }

    /**
     * A type-safe wrapper for a Linux file descriptor.
     * Uses a value class to ensure zero runtime overhead.
     */
    @JvmInline
    public value class FileDescriptor(public val value: Int) {
        public val isValid: Boolean get() = value >= 0
        public val isInvalid: Boolean get() = value < 0

        public companion object {
            /** Represents an invalid or uninitialized file descriptor. */
            public val INVALID: FileDescriptor = FileDescriptor(-1)
        }

        override fun toString(): String = if (isValid) "fd($value)" else "fd(INVALID)"
    }

    /**
     * Represents the result of a native Linux system call.
     */
    public sealed interface SyscallResult {
        /** The call succeeded. */
        public data class Success(val value: Long) : SyscallResult {
            /** Returns the value as an [Int], commonly used for file descriptors. */
            public fun asInt(): Int = value.toInt()

            /** Returns the value as a [FileDescriptor]. */
            public fun asFd(): FileDescriptor = FileDescriptor(value.toInt())
        }

        /** The call failed with a specific Linux [errno]. */
        public data class Error(val errno: Int, val rawValue: Long) : SyscallResult {
            /** Throws an [IllegalStateException] with the given [context]. */
            public fun throwErrno(context: String): Nothing {
                throw IllegalStateException("$context failed with errno=$errno (raw return=$rawValue)")
            }
        }

        /**
         * Unwraps the success value or throws an exception with the given [context].
         */
        public fun getOrThrow(context: String): Long =
            when (this) {
                is Success -> value
                is Error -> throwErrno(context)
            }

        /**
         * Unwraps the success value as a [FileDescriptor] or throws an exception with the given [context].
         */
        public fun getFdOrThrow(context: String): FileDescriptor =
            when (this) {
                is Success -> asFd()
                is Error -> throwErrno(context)
            }

        /** Returns the success value or null if the call failed. */
        public fun getOrNull(): Long? =
            when (this) {
                is Success -> value
                is Error -> null
            }
    }
}

/**
 * Real implementation of NativeEngine using FFM to call Linux system calls.
 */
internal object RealNativeEngine : NativeEngine {
    private val linker: Linker = Linker.nativeLinker()
    private val stdlib: SymbolLookup = linker.defaultLookup()

    private val PRCTL: MethodHandle
    private val SYSCALL: MethodHandle
    private val OPEN: MethodHandle
    private val CLOSE: MethodHandle
    private val SOCKETPAIR: MethodHandle
    private val SOCKET: MethodHandle
    private val BIND: MethodHandle
    private val LISTEN: MethodHandle
    private val ACCEPT: MethodHandle
    private val CONNECT: MethodHandle
    private val SENDMSG: MethodHandle
    private val RECVMSG: MethodHandle
    private val IOCTL_ADDR: MethodHandle
    private val IOCTL_LONG: MethodHandle
    private val PROCESS_VM_READV: MethodHandle
    private val READLINK: MethodHandle
    private val READ: MethodHandle
    private val WRITE: MethodHandle
    private val RECV: MethodHandle
    private val FCNTL: MethodHandle
    private val GETTID: MethodHandle
    private val POLL: MethodHandle

    init {
        PRCTL =
            downcall(
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
        SYSCALL =
            downcall(
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
        OPEN =
            downcall(
                "open",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.captureCallState("errno"),
            )
        CLOSE =
            downcall(
                "close",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                Linker.Option.captureCallState("errno"),
            )
        SOCKETPAIR =
            downcall(
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
        SOCKET =
            downcall(
                "socket",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                ),
                Linker.Option.captureCallState("errno"),
            )
        BIND =
            downcall(
                "bind",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                ),
                Linker.Option.captureCallState("errno"),
            )
        LISTEN =
            downcall(
                "listen",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                Linker.Option.captureCallState("errno"),
            )
        ACCEPT =
            downcall(
                "accept",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                Linker.Option.captureCallState("errno"),
            )
        CONNECT =
            downcall(
                "connect",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                ),
                Linker.Option.captureCallState("errno"),
            )
        SENDMSG =
            downcall(
                "sendmsg",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                ),
                Linker.Option.captureCallState("errno"),
            )
        RECVMSG =
            downcall(
                "recvmsg",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                ),
                Linker.Option.captureCallState("errno"),
            )
        IOCTL_ADDR =
            downcall(
                "ioctl",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                ),
                Linker.Option.captureCallState("errno"),
            )
        IOCTL_LONG =
            downcall(
                "ioctl",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG,
                ),
                Linker.Option.captureCallState("errno"),
            )
        PROCESS_VM_READV =
            downcall(
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
        READLINK =
            downcall(
                "readlink",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                ),
                Linker.Option.captureCallState("errno"),
            )
        READ =
            downcall(
                "read",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                ),
                Linker.Option.captureCallState("errno"),
            )
        WRITE =
            downcall(
                "write",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                ),
                Linker.Option.captureCallState("errno"),
            )
        RECV =
            downcall(
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
        FCNTL =
            downcall(
                "fcntl",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                ),
                Linker.Option.captureCallState("errno"),
            )
        GETTID =
            downcall(
                "gettid",
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
                Linker.Option.captureCallState("errno"),
            )
        POLL =
            downcall(
                "poll",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                ),
                Linker.Option.captureCallState("errno"),
            )
        LayoutValidator.validate()
    }

    private fun Any?.toLong(): Long =
        when (this) {
            is Number -> this.toLong()
            is MemorySegment -> this.address()
            is LinuxNative.FileDescriptor -> this.value.toLong()
            null -> 0L
            else -> throw IllegalArgumentException("Unsupported native call argument type: ${this.javaClass.name}")
        }

    private fun result(ret: Long, errno: Int): LinuxNative.SyscallResult =
        if (ret < 0) LinuxNative.SyscallResult.Error(errno, ret)
        else LinuxNative.SyscallResult.Success(ret)

    context(_: NativeTransaction)
    override fun prctl(
        option: Int,
        arg2: Any?,
        arg3: Any?,
        arg4: Any?,
        arg5: Any?,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret =
            PRCTL.invokeExact(
                capturedState.segment,
                option,
                arg2.toLong(),
                arg3.toLong(),
                arg4.toLong(),
                arg5.toLong(),
            ) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun syscall(
        nr: Long,
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
        a5: Any?,
        a6: Any?,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret =
            SYSCALL.invokeExact(
                capturedState.segment,
                nr,
                a1.toLong(),
                a2.toLong(),
                a3.toLong(),
                a4.toLong(),
                a5.toLong(),
                a6.toLong(),
            ) as Long
        result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun syscall4(
        nr: Long,
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
    ): LinuxNative.SyscallResult = syscall(nr, a1, a2, a3, a4)

    context(_: NativeTransaction)
    override fun open(
        path: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = OPEN.invokeExact(capturedState.segment, path, flags) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    override fun close(fd: LinuxNative.FileDescriptor): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = CLOSE.invokeExact(capturedState.segment, fd.value) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun socketpair(
        domain: Int,
        type: Int,
        protocol: Int,
        sv: MemorySegment,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = SOCKETPAIR.invokeExact(capturedState.segment, domain, type, protocol, sv) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = SOCKET.invokeExact(capturedState.segment, domain, type, protocol) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun bind(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = BIND.invokeExact(capturedState.segment, sockfd.value, addr, addrlen) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun listen(
        sockfd: LinuxNative.FileDescriptor,
        backlog: Int,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = LISTEN.invokeExact(capturedState.segment, sockfd.value, backlog) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun accept(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = ACCEPT.invokeExact(capturedState.segment, sockfd.value, addr, addrlen) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun connect(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = CONNECT.invokeExact(capturedState.segment, sockfd.value, addr, addrlen) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun sendmsg(
        sockfd: LinuxNative.FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = SENDMSG.invokeExact(capturedState.segment, sockfd.value, msg, flags) as Long
        result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun recvmsg(
        sockfd: LinuxNative.FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = RECVMSG.invokeExact(capturedState.segment, sockfd.value, msg, flags) as Long
        result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun ioctl(
        fd: LinuxNative.FileDescriptor,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = IOCTL_ADDR.invokeExact(capturedState.segment, fd.value, request, arg) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun ioctl(
        fd: LinuxNative.FileDescriptor,
        request: Long,
        arg: Long,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = IOCTL_LONG.invokeExact(capturedState.segment, fd.value, request, arg) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun processVmReadv(
        pid: Int,
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret =
            PROCESS_VM_READV.invokeExact(
                capturedState.segment,
                pid,
                localIov,
                liovcnt,
                remoteIov,
                riovcnt,
                flags,
            ) as Long
        result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = READLINK.invokeExact(capturedState.segment, path, buf, bufsiz) as Long
        result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun read(
        fd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = READ.invokeExact(capturedState.segment, fd.value, buf, count) as Long
        result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun write(
        fd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = WRITE.invokeExact(capturedState.segment, fd.value, buf, count) as Long
        result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun recv(
        sockfd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = RECV.invokeExact(capturedState.segment, sockfd.value, buf, len, flags) as Long
        result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun fcntl(
        fd: LinuxNative.FileDescriptor,
        cmd: Int,
        arg: Long,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = FCNTL.invokeExact(capturedState.segment, fd.value, cmd, arg) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    override fun gettid(): Int = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        GETTID.invokeExact(capturedState.segment) as Int
    }

    context(_: NativeTransaction)
    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = POLL.invokeExact(capturedState.segment, fds, nfds, timeout) as Int
        result(ret.toLong(), capturedState.getErrno())
    }

    context(arena: Arena)
    override fun newSockFProg(
        filters: List<BpfInstruction>,
    ): MemorySegment {
        val filterArraySeg = SockFilterSegment.allocateArray(filters.size)
        for (i in filters.indices) {
            val f = filters[i]
            val segment = SockFilterSegment(filterArraySeg.asSlice(i * Layouts.SOCK_FILTER_SIZE, Layouts.SOCK_FILTER_SIZE))
            segment.setCode(f.code)
            segment.setJt(f.jt.toByte())
            segment.setJf(f.jf.toByte())
            segment.setK(f.k)
        }
        val prog = SockFprogSegment.allocate()
        prog.setLen(filters.size.toShort())
        prog.setFilter(filterArraySeg)
        return prog.segment
    }

    private fun downcall(
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
