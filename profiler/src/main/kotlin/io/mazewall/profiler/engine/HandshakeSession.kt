package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.ffi.NativeConstants
import io.mazewall.recover
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

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

        /**
         * Performs the byte-level protocol handshake with the parent JVM.
         * Decouples IPC polling from orchestration.
         */
        @Suppress("ReturnCount", "NestedBlockDepth", "CyclomaticComplexMethod")
        fun performHandshake(
            socketFd: LinuxNative.FileDescriptor,
            transport: ProfilerTransport,
            pollFd: MemorySegment,
            ackBuf: MemorySegment,
            onShutdown: (String) -> Unit
        ): HandshakeSession {
            pollFd.set(ValueLayout.JAVA_SHORT, POLLFD_REVENTS_OFF, 0.toShort())

            while (true) {
                val pollRes = transport.poll(pollFd, 1L, POLL_ACK_TIMEOUT_MS)
                val count = pollRes.recover { errno, _ ->
                    if (errno == NativeConstants.EINTR) return@recover RETRY_SIGNAL
                    return@recover INTERNAL_ERROR_SIGNAL
                }
                if (count == RETRY_SIGNAL) continue
                if (count == INTERNAL_ERROR_SIGNAL || count == 0L) return failed()

                val revents = pollFd.get(ValueLayout.JAVA_SHORT, POLLFD_REVENTS_OFF)
                if ((revents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
                    return readAndProcessAck(socketFd, transport, ackBuf, onShutdown)
                }
                return failed()
            }
        }

        @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
        private fun readAndProcessAck(
            socketFd: LinuxNative.FileDescriptor,
            transport: ProfilerTransport,
            ackBuf: MemorySegment,
            onShutdown: (String) -> Unit
        ): HandshakeSession {
            while (true) {
                val readRes = transport.read(socketFd, ackBuf, ACK_BUF_SIZE)
                val value = readRes.recover { errno, _ ->
                    if (errno == NativeConstants.EINTR) return@recover RETRY_SIGNAL
                    return@recover INTERNAL_ERROR_SIGNAL
                }
                if (value == RETRY_SIGNAL) continue
                if (value <= 0) {
                    return failed()
                }
                for (i in 0 until value.toInt()) {
                    val byte = ackBuf.get(ValueLayout.JAVA_BYTE, i.toLong())
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
            private const val RETRY_SIGNAL = -1L
            private const val INTERNAL_ERROR_SIGNAL = -2L
        }
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
