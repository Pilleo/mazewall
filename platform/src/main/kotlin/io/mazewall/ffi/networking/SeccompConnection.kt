package io.mazewall.ffi.networking

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager

/**
 * Represents the lifecycle of a connection between a seccomp daemon and the tracee JVM.
 */
public sealed class SeccompConnection(
    protected val socketClosedFlag: java.util.concurrent.atomic.AtomicBoolean,
    protected val listenerClosedFlag: java.util.concurrent.atomic.AtomicBoolean,
) : AutoCloseable {
    /** Result of an attempted close. A failed descriptor remains owned for a later retry. */
    public sealed interface CloseResult {
        public data object Closed : CloseResult

        public data class Failed(
            public val failures: List<Exception>,
        ) : CloseResult
    }

    public abstract val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>
    public open val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>? get() = null

    /** Optional custom socket manager used during close(). Defaults to RealSocketManager. */
    public var socketManager: SocketManager = RealSocketManager

    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    public fun closeWithResult(): CloseResult {
        val failures = mutableListOf<Exception>()
        if (!socketClosedFlag.get()) {
            try {
                socketManager.close(socketFd)
                socketClosedFlag.set(true)
            } catch (failure: Exception) {
                failures += failure
            }
        }

        listenerFd?.takeIf { !listenerClosedFlag.get() }?.let { lFd ->
            try {
                socketManager.close(lFd)
                listenerClosedFlag.set(true)
            } catch (failure: Exception) {
                failures += failure
            }
        }
        return if (failures.isEmpty()) CloseResult.Closed else CloseResult.Failed(failures)
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
