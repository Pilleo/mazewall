package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import io.mazewall.recover

/**
 * A type-safe state machine for the seccomp notification handshake.
 */
sealed class HandshakeSession {
    abstract val notifId: Long
    abstract val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>

    /** Handshake initiated, event sent to JVM, waiting for 0xAC ACK. */
    class Active(
        override val notifId: Long,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : HandshakeSession() {
        fun acknowledged() = Success(notifId, listenerFd)
        fun failed() = Failed(notifId, listenerFd)

        /**
         * Performs the byte-level protocol handshake with the parent JVM.
         * Decouples IPC polling from orchestration.
         */
        @Suppress("ReturnCount", "NestedBlockDepth", "CyclomaticComplexMethod")
        fun performHandshake(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
            ioOps: NativeIoOperations,
            pollFd: ManagedSegment,
            ackBuf: ManagedSegment,
            onShutdown: (String) -> Unit
        ): HandshakeSession {
            pollFd.writeShort(Layouts.POLLFD_REVENTS_OFFSET, 0.toShort())

            while (true) {
                val pollRes = LinuxNative.withTransaction { ioOps.raw.poll(pollFd, 1L, POLL_ACK_TIMEOUT_MS) }
                val count = pollRes.recover { errno, _ ->
                    if (errno == NativeConstants.EINTR) return@recover RETRY_SIGNAL
                    return@recover INTERNAL_ERROR_SIGNAL
                }
                if (count == RETRY_SIGNAL) continue
                if (count == INTERNAL_ERROR_SIGNAL || count == 0L) return failed()

                val revents = pollFd.readShort(Layouts.POLLFD_REVENTS_OFFSET)
                if ((revents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
                    return readAndProcessAck(socketFd, ioOps, ackBuf, onShutdown)
                }
                return failed()
            }
        }

        @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
        private fun readAndProcessAck(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
            ioOps: NativeIoOperations,
            ackBuf: ManagedSegment,
            onShutdown: (String) -> Unit
        ): HandshakeSession {
            while (true) {
                val readRes = ioOps.read(socketFd, ackBuf, ACK_BUF_SIZE)
                val value = readRes.recover { errno, _ ->
                    if (errno == NativeConstants.EINTR) return@recover RETRY_SIGNAL
                    return@recover INTERNAL_ERROR_SIGNAL
                }
                if (value == RETRY_SIGNAL) continue
                if (value <= 0) {
                    return failed()
                }
                for (i in 0 until value.toInt()) {
                    val byte = ackBuf.readByte(i.toLong())
                    if (byte == PROTOCOL_ACK_BYTE) return acknowledged()
                    if (byte == SHUTDOWN_COMMAND_BYTE) {
                        onShutdown("Parent Command during notification")
                        return failed()
                    }
                }
                break
            }
            return failed()
        }

        private companion object {
            private const val POLL_ACK_TIMEOUT_MS = 1000
            private const val ACK_BUF_SIZE = 1L
            private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
            private const val SHUTDOWN_COMMAND_BYTE = 0x53.toByte() // 'S'
            private const val RETRY_SIGNAL = -1L
            private const val INTERNAL_ERROR_SIGNAL = -2L
        }
    }

    /** Handshake successful, ready to send SECCOMP_USER_NOTIF_FLAG_CONTINUE. */
    class Success(
        override val notifId: Long,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : HandshakeSession()

    /** Handshake failed (timeout, error, or shutdown), must send an error or kill thread. */
    class Failed(
        override val notifId: Long,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : HandshakeSession()
}
