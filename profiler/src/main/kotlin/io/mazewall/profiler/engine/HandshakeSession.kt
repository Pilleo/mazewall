package io.mazewall.profiler.engine

import io.mazewall.LinuxNative

/**
 * A type-safe state machine for the seccomp notification handshake.
 */
sealed class HandshakeSession {
    abstract val notifId: Long
    abstract val listenerFd: LinuxNative.FileDescriptor

    /** Handshake initiated, event sent to JVM, waiting for 0xAC ACK. */
    class Active(
        override val notifId: Long,
        override val listenerFd: LinuxNative.FileDescriptor,
    ) : HandshakeSession() {
        fun acknowledged() = Success(notifId, listenerFd)
        fun failed() = Failed(notifId, listenerFd)
    }

    /** Handshake successful, ready to send SECCOMP_USER_NOTIF_FLAG_CONTINUE. */
    class Success(
        override val notifId: Long,
        override val listenerFd: LinuxNative.FileDescriptor,
    ) : HandshakeSession()

    /** Handshake failed (timeout, error, or shutdown), must send an error or kill thread. */
    class Failed(
        override val notifId: Long,
        override val listenerFd: LinuxNative.FileDescriptor,
    ) : HandshakeSession()
}
