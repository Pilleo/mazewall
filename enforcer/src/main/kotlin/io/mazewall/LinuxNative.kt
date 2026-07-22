package io.mazewall

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.internal.RealTransactionManager
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Entry point for all native Linux interactions.
 *
 * ARCHITECTURAL INVARIANT: The FFM Transaction lifecycle (Arena management) is
 * decoupled from the native engine via an internal TransactionManager. This separation
 * ensures that [LinuxNative] remains mockable and that memory allocation strategies
 * can be swapped (e.g., for arena pooling) without modifying call dispatch logic.
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
    override fun <T> withTransaction(block: NativeTransaction.() -> T): T {
        return transactionManager.withTransaction(block)
    }

    override val fileSystem: NativeFileSystem get() = engine.fileSystem
    override val networking: NativeNetworking get() = engine.networking
    override val process: NativeProcess get() = engine.process
    override val memory: NativeMemory get() = engine.memory
    override val raw: RawSyscallOperations get() = engine.raw

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
