package io.mazewall

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.LayoutValidator
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.ErrnoSegment
import io.mazewall.ffi.memory.SockFilterSegment
import io.mazewall.ffi.memory.SockFprogSegment
import io.mazewall.ffi.memory.nativeScope
import io.mazewall.seccomp.BpfInstruction
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
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

    @Volatile
    private var transactionManager: TransactionManager = RealTransactionManager

    /**
     * Swaps the active transaction manager. Used for testing.
     */
    @Suppress("spotbugs:ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    fun setTransactionManager(newManager: TransactionManager) {
        transactionManager = newManager
    }

    /**
     * Restores the default RealTransactionManager.
     */
    @Suppress("spotbugs:ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    fun resetTransactionManager() {
        transactionManager = RealTransactionManager
    }

    /**
     * Executes the given [block] within a [NativeTransaction] context.
     * Raw system calls and sensitive native operations are only available within this scope.
     */
    public fun <T> withTransaction(block: NativeTransaction.() -> T): T {
        return transactionManager.withTransaction(block)
    }

    override val fileSystem: NativeFileSystem get() = engine.fileSystem
    override val networking: NativeNetworking get() = engine.networking
    override val process: NativeProcess get() = engine.process
    override val memory: NativeMemory get() = engine.memory

    context(_: NativeTransaction)
    override fun syscall(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
        a5: io.mazewall.core.NativeArg,
        a6: io.mazewall.core.NativeArg,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> = engine.syscall(nr, a1, a2, a3, a4, a5, a6)

    context(_: NativeTransaction)
    override fun syscall4(
        nr: Long,
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> = engine.syscall4(nr, a1, a2, a3, a4)

    context(_: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: MemorySegment,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> = engine.ioctl(fd, request, arg)

    context(_: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> = engine.ioctl(fd, request, arg)

    context(_: NativeTransaction)
    override fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> = engine.fcntl(fd, cmd, arg)

    context(_: NativeTransaction)
    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): SyscallResult<Long, SyscallHandledState.Unhandled> = engine.poll(fds, nfds, timeout)

    /**
     * Marker interface for system call handling states.
     */
    public sealed interface SyscallHandledState {
        public interface Handled : SyscallHandledState
        public interface Unhandled : SyscallHandledState
    }

    /**
     * Represents the result of a native Linux system call.
     *
     * @param T The type of the value returned on success.
     */
    public sealed interface SyscallResult<out T, out Handled : SyscallHandledState> {
        /** The call succeeded. */
        public data class Success<out T, out Handled : SyscallHandledState>(val value: T) : SyscallResult<T, Handled>

        /** The call failed with a specific Linux [errno]. */
        public data class Error<out Handled : SyscallHandledState>(val errno: Int, val rawValue: Long) : SyscallResult<Nothing, Handled> {
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

@OptIn(ExperimentalContracts::class)
public fun <T, H : LinuxNative.SyscallHandledState> LinuxNative.SyscallResult<T, H>.isSuccess(): Boolean {
    contract {
        returns(true) implies (this@isSuccess is LinuxNative.SyscallResult.Success<T, H>)
    }
    return this is LinuxNative.SyscallResult.Success
}

@OptIn(ExperimentalContracts::class)
public fun <T, H : LinuxNative.SyscallHandledState> LinuxNative.SyscallResult<T, H>.isFailure(): Boolean {
    contract {
        returns(true) implies (this@isFailure is LinuxNative.SyscallResult.Error<H>)
    }
    return this is LinuxNative.SyscallResult.Error
}

/**
 * Transforms the success value using [transform].
 */
@OptIn(ExperimentalContracts::class)
public inline fun <T, R, H : LinuxNative.SyscallHandledState> LinuxNative.SyscallResult<T, H>.map(transform: (T) -> R): LinuxNative.SyscallResult<R, H> {
    contract {
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is LinuxNative.SyscallResult.Success -> LinuxNative.SyscallResult.Success(transform(value))
        is LinuxNative.SyscallResult.Error -> this
    }
}

/**
 * Transforms the success value using [transform] which returns another [LinuxNative.SyscallResult].
 */
@OptIn(ExperimentalContracts::class)
public inline fun <T, R, H : LinuxNative.SyscallHandledState> LinuxNative.SyscallResult<T, H>.flatMap(
    transform: (T) -> LinuxNative.SyscallResult<R, H>
): LinuxNative.SyscallResult<R, H> {
    contract {
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is LinuxNative.SyscallResult.Success -> transform(value)
        is LinuxNative.SyscallResult.Error -> this
    }
}

/**
 * Executes [action] if the call succeeded.
 */
@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalContracts::class)
public inline fun <T> LinuxNative.SyscallResult<T, *>.onSuccess(action: (T) -> Unit): LinuxNative.SyscallResult<T, LinuxNative.SyscallHandledState.Handled> {
    contract {
        callsInPlace(action, InvocationKind.AT_MOST_ONCE)
    }
    if (this is LinuxNative.SyscallResult.Success) action(value)
    return this as LinuxNative.SyscallResult<T, LinuxNative.SyscallHandledState.Handled>
}

/**
 * Executes [action] if the call failed.
 */
@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalContracts::class)
public inline fun <T> LinuxNative.SyscallResult<T, *>.onFailure(
    action: (errno: Int, rawValue: Long) -> Unit
): LinuxNative.SyscallResult<T, LinuxNative.SyscallHandledState.Handled> {
    contract {
        callsInPlace(action, InvocationKind.AT_MOST_ONCE)
    }
    if (this is LinuxNative.SyscallResult.Error) action(errno, rawValue)
    return this as LinuxNative.SyscallResult<T, LinuxNative.SyscallHandledState.Handled>
}

/**
 * Recovers from an error by applying [transform].
 */
@OptIn(ExperimentalContracts::class)
public inline fun <T> LinuxNative.SyscallResult<T, *>.recover(
    transform: (errno: Int, rawValue: Long) -> @UnsafeVariance T
): T {
    contract {
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is LinuxNative.SyscallResult.Success -> value
        is LinuxNative.SyscallResult.Error -> transform(errno, rawValue)
    }
}

/**
 * Extension properties for [LinuxNative.SyscallResult] of [Long] to provide
 * convenience conversions for common native result types.
 */
public fun LinuxNative.SyscallResult.Success<Long, *>.asLong(): Long = value

/**
 * Returns the success value as a [FileDescriptor] of [FileDescriptorRole.Generic].
 */
public fun LinuxNative.SyscallResult.Success<Long, *>.asFd(): FileDescriptor<FileDescriptorRole.Generic, FdState.Open> =
    FileDescriptor.unsafe(value.toInt())

/**
 * Convenience extension for [LinuxNative.SyscallResult] of [Long].
 */
public fun LinuxNative.SyscallResult<Long, *>.asInt(): Int =
    when (this) {
        is LinuxNative.SyscallResult.Success -> value.toInt()
        is LinuxNative.SyscallResult.Error -> throwErrno("asInt")
    }

/**
 * Returns the success value as a [FileDescriptor] of [FileDescriptorRole.Generic] or throws.
 */
public fun LinuxNative.SyscallResult<Long, *>.getFdOrThrow(context: String): FileDescriptor<FileDescriptorRole.Generic, FdState.Open> =
    when (this) {
        is LinuxNative.SyscallResult.Success -> FileDescriptor.unsafe(value.toInt())
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
        a1: io.mazewall.core.NativeArg,
        a2: io.mazewall.core.NativeArg,
        a3: io.mazewall.core.NativeArg,
        a4: io.mazewall.core.NativeArg,
        a5: io.mazewall.core.NativeArg,
        a6: io.mazewall.core.NativeArg,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            val capturedState = ErrnoSegment.allocate()
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
            RealNativeHelper.result(ret, capturedState.getErrno())
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
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(fd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = IOCTL_ADDR.invokeExact(capturedState.segment, fd.value, request, arg) as Int
            RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(fd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = IOCTL_LONG.invokeExact(capturedState.segment, fd.value, request, arg) as Int
            RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun fcntl(
        fd: FileDescriptor<*, FdState.Open>,
        cmd: Int,
        arg: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(fd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = FCNTL.invokeExact(capturedState.segment, fd.value, cmd, arg) as Int
            RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
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
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            val capturedState = ErrnoSegment.allocate()
            val ret = OPEN.invokeExact(capturedState.segment, path, flags) as Int
            RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
        }

    override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        val ret = CLOSE.invokeExact(capturedState.segment, fd.value) as Int
        RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
    }

    context(_: NativeTransaction)
    override fun readlink(
        path: MemorySegment,
        buf: MemorySegment,
        bufsiz: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
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
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
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
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            val capturedState = ErrnoSegment.allocate()
            val ret = SOCKET.invokeExact(capturedState.segment, domain, type, protocol) as Int
            RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun bind(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = BIND.invokeExact(capturedState.segment, sockfd.value, addr, addrlen) as Int
            RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun listen(
        sockfd: FileDescriptor<*, FdState.Open>,
        backlog: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = LISTEN.invokeExact(capturedState.segment, sockfd.value, backlog) as Int
            RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun accept(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: MemorySegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = ACCEPT.invokeExact(capturedState.segment, sockfd.value, addr, addrlen) as Int
            RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun connect(
        sockfd: FileDescriptor<*, FdState.Open>,
        addr: MemorySegment,
        addrlen: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = CONNECT.invokeExact(capturedState.segment, sockfd.value, addr, addrlen) as Int
            RealNativeHelper.result(ret.toLong(), capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun sendmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = SENDMSG.invokeExact(capturedState.segment, sockfd.value, msg, flags) as Long
            RealNativeHelper.result(ret, capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun recvmsg(
        sockfd: FileDescriptor<*, FdState.Open>,
        msg: MemorySegment,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = RECVMSG.invokeExact(capturedState.segment, sockfd.value, msg, flags) as Long
            RealNativeHelper.result(ret, capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(sockfd.isValid) { "FileDescriptor is invalid or closed" }
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

    override fun gettid(): io.mazewall.core.Tid = nativeScope {
        val capturedState = ErrnoSegment.allocate()
        io.mazewall.core.Tid(GETTID.invokeExact(capturedState.segment) as Int)
    }

    context(_: NativeTransaction)
    override fun prctl(command: io.mazewall.core.PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            val capturedState = ErrnoSegment.allocate()
            val ret =
                PRCTL.invokeExact(
                    capturedState.segment,
                    command.option,
                    command.arg2.asLong,
                    command.arg3.asLong,
                    command.arg4.asLong,
                    command.arg5.asLong,
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
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
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
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(fd.isValid) { "FileDescriptor is invalid or closed" }
            val capturedState = ErrnoSegment.allocate()
            val ret = READ.invokeExact(capturedState.segment, fd.value, buf, count) as Long
            RealNativeHelper.result(ret, capturedState.getErrno())
        }

    context(_: NativeTransaction)
    override fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        nativeScope {
            require(fd.isValid) { "FileDescriptor is invalid or closed" }
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

/**
 * Default implementation of TransactionManager using a static singleton transaction context.
 */
public object RealTransactionManager : TransactionManager {
    @JvmSynthetic
    @PublishedApi
    internal val TRANSACTION_INSTANCE: NativeTransaction = object : NativeTransaction {}

    override fun <T> withTransaction(block: NativeTransaction.() -> T): T {
        return TRANSACTION_INSTANCE.block()
    }
}
