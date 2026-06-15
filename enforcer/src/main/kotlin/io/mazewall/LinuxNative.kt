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

    override val fileSystem: NativeFileSystem get() = engine.fileSystem
    override val networking: NativeNetworking get() = engine.networking
    override val process: NativeProcess get() = engine.process
    override val memory: NativeMemory get() = engine.memory

    context(_: NativeTransaction)
    override fun syscall(
        nr: Long,
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
        a5: Any?,
        a6: Any?,
    ): SyscallResult<Long> = engine.syscall(nr, a1, a2, a3, a4, a5, a6)

    context(_: NativeTransaction)
    override fun syscall4(
        nr: Long,
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
    ): SyscallResult<Long> = engine.syscall4(nr, a1, a2, a3, a4)

    context(_: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor,
        request: Long,
        arg: MemorySegment,
    ): SyscallResult<Long> = engine.ioctl(fd, request, arg)

    context(_: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor,
        request: Long,
        arg: Long,
    ): SyscallResult<Long> = engine.ioctl(fd, request, arg)

    context(_: NativeTransaction)
    override fun fcntl(
        fd: FileDescriptor,
        cmd: Int,
        arg: Long,
    ): SyscallResult<Long> = engine.fcntl(fd, cmd, arg)

    context(_: NativeTransaction)
    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): SyscallResult<Long> = engine.poll(fds, nfds, timeout)

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
     *
     * @param T The type of the value returned on success.
     */
    public sealed interface SyscallResult<out T> {
        /** The call succeeded. */
        public data class Success<out T>(val value: T) : SyscallResult<T>

        /** The call failed with a specific Linux [errno]. */
        public data class Error(val errno: Int, val rawValue: Long) : SyscallResult<Nothing> {
            /** Throws an [IllegalStateException] with the given [context]. */
            public fun throwErrno(context: String): Nothing {
                throw IllegalStateException("$context failed with errno=$errno (raw return=$rawValue)")
            }
        }

        /**
         * Returns the success value or throws an exception if the call failed.
         */
        public fun getOrThrow(context: String): T =
            when (this) {
                is Success -> value
                is Error -> throwErrno(context)
            }

        /**
         * Returns the success value or null if the call failed.
         */
        public fun getOrNull(): T? =
            when (this) {
                is Success -> value
                is Error -> null
            }
    }
}

/**
 * Transforms the success value using [transform].
 */
public inline fun <T, R> LinuxNative.SyscallResult<T>.map(transform: (T) -> R): LinuxNative.SyscallResult<R> =
    when (this) {
        is LinuxNative.SyscallResult.Success -> LinuxNative.SyscallResult.Success(transform(value))
        is LinuxNative.SyscallResult.Error -> this
    }

/**
 * Transforms the success value using [transform] which returns another [LinuxNative.SyscallResult].
 */
public inline fun <T, R> LinuxNative.SyscallResult<T>.flatMap(
    transform: (T) -> LinuxNative.SyscallResult<R>
): LinuxNative.SyscallResult<R> =
    when (this) {
        is LinuxNative.SyscallResult.Success -> transform(value)
        is LinuxNative.SyscallResult.Error -> this
    }

/**
 * Executes [action] if the call succeeded.
 */
public inline fun <T> LinuxNative.SyscallResult<T>.onSuccess(action: (T) -> Unit): LinuxNative.SyscallResult<T> {
    if (this is LinuxNative.SyscallResult.Success) action(value)
    return this
}

/**
 * Executes [action] if the call failed.
 */
public inline fun <T> LinuxNative.SyscallResult<T>.onFailure(
    action: (errno: Int, rawValue: Long) -> Unit
): LinuxNative.SyscallResult<T> {
    if (this is LinuxNative.SyscallResult.Error) action(errno, rawValue)
    return this
}

/**
 * Recovers from an error by applying [transform].
 */
public inline fun <T> LinuxNative.SyscallResult<T>.recover(
    transform: (errno: Int, rawValue: Long) -> @UnsafeVariance T
): T =
    when (this) {
        is LinuxNative.SyscallResult.Success -> value
        is LinuxNative.SyscallResult.Error -> transform(errno, rawValue)
    }

/**
 * Extension properties for [LinuxNative.SyscallResult] of [Long] to provide
 * convenience conversions for common native result types.
 */
public fun LinuxNative.SyscallResult.Success<Long>.asInt(): Int = value.toInt()

/**
 * Returns the success value as a [LinuxNative.FileDescriptor].
 */
public fun LinuxNative.SyscallResult.Success<Long>.asFd(): LinuxNative.FileDescriptor =
    LinuxNative.FileDescriptor(value.toInt())

/**
 * Convenience extension for [LinuxNative.SyscallResult] of [Long].
 */
public fun LinuxNative.SyscallResult<Long>.asInt(): Int =
    when (this) {
        is LinuxNative.SyscallResult.Success -> value.toInt()
        is LinuxNative.SyscallResult.Error -> throwErrno("asInt")
    }

/**
 * Returns the success value as a [LinuxNative.FileDescriptor] or throws.
 */
public fun LinuxNative.SyscallResult<Long>.getFdOrThrow(context: String): LinuxNative.FileDescriptor =
    when (this) {
        is LinuxNative.SyscallResult.Success -> LinuxNative.FileDescriptor(value.toInt())
        is LinuxNative.SyscallResult.Error -> throwErrno(context)
    }

/**
 * Real implementation of NativeEngine using FFM to call Linux system calls.
 */
internal object RealNativeEngine : NativeEngine {
    override val fileSystem: NativeFileSystem = RealNativeFileSystem
    override val networking: NativeNetworking = RealNativeNetworking
    override val process: NativeProcess = RealNativeProcess
    override val memory: NativeMemory = RealNativeMemory

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
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
        a5: Any?,
        a6: Any?,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret =
            SYSCALL.invokeExact(
                capturedState.segment,
                nr,
                RealNativeHelper.toLong(a1),
                RealNativeHelper.toLong(a2),
                RealNativeHelper.toLong(a3),
                RealNativeHelper.toLong(a4),
                RealNativeHelper.toLong(a5),
                RealNativeHelper.toLong(a6),
            ) as Long
        RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun syscall4(
        nr: Long,
        a1: Any?,
        a2: Any?,
        a3: Any?,
        a4: Any?,
    ): LinuxNative.SyscallResult<Long> =
 syscall(nr, a1, a2, a3, a4)

    context(_: NativeTransaction)
    override fun ioctl(
        fd: LinuxNative.FileDescriptor,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = IOCTL_ADDR.invokeExact(capturedState.segment, fd.value, request, arg) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun ioctl(
        fd: LinuxNative.FileDescriptor,
        request: Long,
        arg: Long,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = IOCTL_LONG.invokeExact(capturedState.segment, fd.value, request, arg) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun fcntl(
        fd: LinuxNative.FileDescriptor,
        cmd: Int,
        arg: Long,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = FCNTL.invokeExact(capturedState.segment, fd.value, cmd, arg) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = POLL.invokeExact(capturedState.segment, fds, nfds, timeout) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
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

    context(_: NativeTransaction)
    override fun open(
        path: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = OPEN.invokeExact(capturedState.segment, path, flags) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    override fun close(fd: LinuxNative.FileDescriptor): LinuxNative.SyscallResult<Long> = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = CLOSE.invokeExact(capturedState.segment, fd.value) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = READLINK.invokeExact(capturedState.segment, path, buf, bufsiz) as Long
        RealNativeHelper.result(ret, capturedState.getErrno())
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
        sv: MemorySegment,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = SOCKETPAIR.invokeExact(capturedState.segment, domain, type, protocol, sv) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = SOCKET.invokeExact(capturedState.segment, domain, type, protocol) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun bind(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = BIND.invokeExact(capturedState.segment, sockfd.value, addr, addrlen) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun listen(
        sockfd: LinuxNative.FileDescriptor,
        backlog: Int,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = LISTEN.invokeExact(capturedState.segment, sockfd.value, backlog) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun accept(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = ACCEPT.invokeExact(capturedState.segment, sockfd.value, addr, addrlen) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun connect(
        sockfd: LinuxNative.FileDescriptor,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = CONNECT.invokeExact(capturedState.segment, sockfd.value, addr, addrlen) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun sendmsg(
        sockfd: LinuxNative.FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = SENDMSG.invokeExact(capturedState.segment, sockfd.value, msg, flags) as Long
        RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun recvmsg(
        sockfd: LinuxNative.FileDescriptor,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = RECVMSG.invokeExact(capturedState.segment, sockfd.value, msg, flags) as Long
        RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun recv(
        sockfd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = RECV.invokeExact(capturedState.segment, sockfd.value, buf, len, flags) as Long
        RealNativeHelper.result(ret, capturedState.getErrno())
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

    override fun gettid(): io.mazewall.core.Pid = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        io.mazewall.core.Pid(GETTID.invokeExact(capturedState.segment) as Int)
    }

    context(_: NativeTransaction)
    override fun prctl(
        option: Int,
        arg2: Any?,
        arg3: Any?,
        arg4: Any?,
        arg5: Any?,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret =
            PRCTL.invokeExact(
                capturedState.segment,
                option,
                RealNativeHelper.toLong(arg2),
                RealNativeHelper.toLong(arg3),
                RealNativeHelper.toLong(arg4),
                RealNativeHelper.toLong(arg5),
            ) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
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
        localIov: MemorySegment,
        liovcnt: Long,
        remoteIov: MemorySegment,
        riovcnt: Long,
        flags: Long,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret =
            PROCESS_VM_READV.invokeExact(
                capturedState.segment,
                pid.value,
                localIov,
                liovcnt,
                remoteIov,
                riovcnt,
                flags,
            ) as Long
        RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun read(
        fd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = READ.invokeExact(capturedState.segment, fd.value, buf, count) as Long
        RealNativeHelper.result(ret, capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun write(
        fd: LinuxNative.FileDescriptor,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long> =
 nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = WRITE.invokeExact(capturedState.segment, fd.value, buf, count) as Long
        RealNativeHelper.result(ret, capturedState.getErrno())
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
}

internal object RealNativeHelper {
    private val linker: Linker = Linker.nativeLinker()
    private val stdlib: SymbolLookup = linker.defaultLookup()

    fun toLong(value: Any?): Long =
        when (value) {
            is Number -> value.toLong()
            is MemorySegment -> value.address()
            is LinuxNative.FileDescriptor -> value.value.toLong()
            is io.mazewall.core.Pid -> value.value.toLong()
            is io.mazewall.core.Uid -> value.value.toLong()
            is io.mazewall.core.MemoryAddress -> value.value
            null -> 0L
            else -> throw IllegalArgumentException("Unsupported native call argument type: ${value.javaClass.name}")
        }

    fun result(ret: Long, errno: Int): LinuxNative.SyscallResult<Long> =
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
