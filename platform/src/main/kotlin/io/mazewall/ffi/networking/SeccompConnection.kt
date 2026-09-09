package io.mazewall.ffi.networking

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeCloseFailure
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException

/**
 * Represents the lifecycle of a connection between a seccomp daemon and the tracee JVM.
 */
public sealed class SeccompConnection(
    protected val socketClosedFlag: java.util.concurrent.atomic.AtomicBoolean,
    protected val listenerClosedFlag: java.util.concurrent.atomic.AtomicBoolean,
) : AutoCloseable {
    /** Failed pre-dispatch closes remain owned; native close results are terminal even on error. */
    public sealed interface CloseResult {
        public data object Closed : CloseResult

        public data class Failed(
            public val failures: List<Exception>,
        ) : CloseResult
    }

    public abstract val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>
    public open val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>? get() = null

    /**
     * Optional close implementation. It is injected once during connection setup and then frozen,
     * so a live session cannot switch descriptor ownership semantics mid-flight.
     */
    public var socketManager: SocketManager = RealSocketManager
        set(value) {
            check(!socketManagerInjected || field === value) { "socket manager is immutable after connection setup" }
            field = value
            socketManagerInjected = true
        }
    private var socketManagerInjected: Boolean = false

    @Synchronized
    public fun closeWithResult(): CloseResult = closeWithResult { }

    /** Owners persist the result before cancellation propagates or interruption is restored. */
    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    internal fun closeWithResult(recordResult: (CloseResult) -> Unit): CloseResult {
        val failures = mutableListOf<Exception>()
        if (!socketClosedFlag.get()) {
            try {
                socketManager.close(socketFd)
                socketClosedFlag.set(true)
            } catch (failure: Exception) {
                if (failure is NativeCloseFailure) socketClosedFlag.set(true)
                failures += failure
            }
        }

        listenerFd?.takeIf { !listenerClosedFlag.get() }?.let { lFd ->
            try {
                socketManager.close(lFd)
                listenerClosedFlag.set(true)
            } catch (failure: Exception) {
                if (failure is NativeCloseFailure) listenerClosedFlag.set(true)
                failures += failure
            }
        }
        val result = if (failures.isEmpty()) CloseResult.Closed else CloseResult.Failed(failures.toList())
        recordResult(result)
        if (failures.any { it is InterruptedException || it is ClosedByInterruptException }) {
            Thread.currentThread().interrupt()
        }
        failures.filterIsInstance<CancellationException>().firstOrNull()?.let { cancellation ->
            failures.filter { it !== cancellation }.forEach(cancellation::addSuppressed)
            throw cancellation
        }
        return result
    }

    /** Only descriptors whose native close has not been attempted remain available for retry. */
    @Synchronized
    internal fun retryableOwnership(): List<FileDescriptor<*, FdState.Open, FdOwnership.Owned>> =
        buildList {
        if (!socketClosedFlag.get()) add(socketFd)
        listenerFd?.takeIf { !listenerClosedFlag.get() }?.let(::add)
    }

    override fun close() {
        // Keep AutoCloseable source compatibility; callers needing recovery use closeWithResult().
        closeWithResult()
    }

    /** Records that the session reactor closed the control socket while retaining listener ownership. */
    public fun markSocketClosed() {
        socketClosedFlag.set(true)
    }

    /** Initial state: Connection accepted, waiting to receive the seccomp listener FD. */
    public data class Accepted(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        private val sharedSocketClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic
            .AtomicBoolean(false),
        private val sharedListenerClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic
            .AtomicBoolean(false),
    ) : SeccompConnection(sharedSocketClosedFlag, sharedListenerClosedFlag) {
        public fun attachFd(listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>): FdAttached =
            FdAttached(socketFd, listenerFd, sharedSocketClosedFlag, sharedListenerClosedFlag).also {
                it.socketManager = this.socketManager
            }
    }

    /** Intermediate state: Listener FD received, waiting to send the 0xAC ACK byte. */
    public data class FdAttached(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        private val sharedSocketClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic
            .AtomicBoolean(false),
        private val sharedListenerClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic
            .AtomicBoolean(false),
    ) : SeccompConnection(sharedSocketClosedFlag, sharedListenerClosedFlag) {
        public fun handshakeComplete(): Active =
            Active(socketFd, listenerFd, sharedSocketClosedFlag, sharedListenerClosedFlag).also {
                it.socketManager = this.socketManager
            }
    }

    /** Established state: Handshake complete, session is now active and polling. */
    public data class Active(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        private val sharedSocketClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic
            .AtomicBoolean(false),
        private val sharedListenerClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic
            .AtomicBoolean(false),
    ) : SeccompConnection(sharedSocketClosedFlag, sharedListenerClosedFlag)
}
