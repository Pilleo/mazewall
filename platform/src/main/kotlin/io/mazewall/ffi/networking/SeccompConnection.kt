package io.mazewall.ffi.networking

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
    public abstract val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>
    public open val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? get() = null

    /** Optional custom socket manager used during close(). Defaults to RealSocketManager. */
    public var socketManager: SocketManager = RealSocketManager

    override fun close() {
        if (socketClosedFlag.compareAndSet(false, true)) {
            try {
                socketManager.close(socketFd)
            } catch (_: Exception) {}
        }

        listenerFd?.takeIf { listenerClosedFlag.compareAndSet(false, true) }?.let { lFd ->
            try {
                socketManager.close(lFd)
            } catch (_: Exception) {}
        }
    }

    /** Records that the session reactor closed the control socket while retaining listener ownership. */
    public fun markSocketClosed() {
        socketClosedFlag.set(true)
    }

    /** Initial state: Connection accepted, waiting to receive the seccomp listener FD. */
    public data class Accepted(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        private val sharedSocketClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        private val sharedListenerClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
    ) : SeccompConnection(sharedSocketClosedFlag, sharedListenerClosedFlag) {
        public fun attachFd(listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>): FdAttached =
            FdAttached(socketFd, listenerFd, sharedSocketClosedFlag, sharedListenerClosedFlag).also {
                it.socketManager = this.socketManager
            }
    }

    /** Intermediate state: Listener FD received, waiting to send the 0xAC ACK byte. */
    public data class FdAttached(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        private val sharedSocketClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        private val sharedListenerClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
    ) : SeccompConnection(sharedSocketClosedFlag, sharedListenerClosedFlag) {
        public fun handshakeComplete(): Active =
            Active(socketFd, listenerFd, sharedSocketClosedFlag, sharedListenerClosedFlag).also {
                it.socketManager = this.socketManager
            }
    }

    /** Established state: Handshake complete, session is now active and polling. */
    public data class Active(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        private val sharedSocketClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        private val sharedListenerClosedFlag: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
    ) : SeccompConnection(sharedSocketClosedFlag, sharedListenerClosedFlag)
}
