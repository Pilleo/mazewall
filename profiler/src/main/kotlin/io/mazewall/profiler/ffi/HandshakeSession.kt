package io.mazewall.profiler.ffi

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.recover
import io.mazewall.profiler.internal.engine.ACK_BUF_SIZE
import io.mazewall.profiler.internal.engine.PASS_THROUGH_COMMAND_BYTE
import io.mazewall.profiler.internal.engine.POLLFD_REVENTS_OFF
import io.mazewall.profiler.internal.engine.POLL_ACK_TIMEOUT_MS
import io.mazewall.profiler.internal.engine.PROTOCOL_ACK_BYTE
import io.mazewall.profiler.internal.engine.ProfilerAckOutcome
import io.mazewall.profiler.internal.engine.ProfilerHandshakeFailure
import io.mazewall.profiler.internal.engine.SHUTDOWN_COMMAND_BYTE
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * A type-safe state machine for the seccomp notification handshake.
 */
internal sealed class HandshakeSession {
    abstract val notifId: Long
    abstract val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>

    /** Handshake initiated, event sent to JVM, waiting for 0xAC ACK. */
    class Active(
        override val notifId: Long,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    ) : HandshakeSession() {
        fun acknowledged(deferredShutdownReason: String? = null) = Success(notifId, listenerFd, deferredShutdownReason)

        fun failed(reason: ProfilerHandshakeFailure = ProfilerHandshakeFailure.Transport) = Failed(notifId, listenerFd, reason)

        fun passedThrough() = PassedThrough(notifId, listenerFd)

        /** SUPPRESSION-RATIONALE: Each byte-level handshake outcome returns at its boundary to make terminal reply and ACK failure handling explicit. */
        fun performHandshake(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            ioOps: NativeIoOperations,
            pollFd: MemorySegment,
            ackBuf: MemorySegment,
            onShutdown: (String) -> Unit,
        ): HandshakeSession =
            performHandshake(
                socketFd,
                ioOps,
                pollFd,
                ackBuf,
                onShutdown,
                Deadline.afterMillis(POLL_ACK_TIMEOUT_MS.toLong()),
            )

        /** Perform both handshake phases against one monotonic deadline. */
        fun performHandshake(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            ioOps: NativeIoOperations,
            pollFd: MemorySegment,
            ackBuf: MemorySegment,
            onShutdown: (String) -> Unit,
            deadline: Deadline,
        ): HandshakeSession {
            pollFd.set(ValueLayout.JAVA_SHORT, POLLFD_REVENTS_OFF, 0.toShort())

            var attempts = 0
            while (attempts++ < MAX_ATTEMPTS) {
                val remainingMillis = deadline.remainingMillis()
                if (remainingMillis <= 0) return failed(ProfilerHandshakeFailure.Timeout)
                val pollRes = ioOps.raw.poll(ConfinedSegment(pollFd), 1L, remainingMillis)
                val count = pollRes.recover { errno, _ ->
                    if (errno == NativeConstants.EINTR) return@recover RETRY_SIGNAL
                    return@recover INTERNAL_ERROR_SIGNAL
                }
                if (count == RETRY_SIGNAL) continue
                if (count == INTERNAL_ERROR_SIGNAL) return failed()
                if (count == 0L) return failed(ProfilerHandshakeFailure.Timeout)

                val revents = pollFd.get(ValueLayout.JAVA_SHORT, POLLFD_REVENTS_OFF)
                if ((revents.toInt() and NativeConstants.POLLIN.toInt()) != 0) {
                    return readAndProcessAck(socketFd, ioOps, ackBuf, onShutdown, deadline)
                }
                return failed()
            }
            return failed(ProfilerHandshakeFailure.Timeout)
        }

        private fun readAndProcessAck(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            ioOps: NativeIoOperations,
            ackBuf: MemorySegment,
            onShutdown: (String) -> Unit,
            deadline: Deadline,
        ): HandshakeSession {
            var attempts = 0
            while (attempts++ < MAX_ATTEMPTS) {
                val remainingMillis = deadline.remainingMillis()
                if (remainingMillis <= 0) return failed(ProfilerHandshakeFailure.Timeout)
                val readRes = ioOps.read(socketFd, ackBuf, ACK_BUF_SIZE)
                val value = readRes.recover { errno, _ ->
                    if (errno == NativeConstants.EINTR) return@recover RETRY_SIGNAL
                    return@recover INTERNAL_ERROR_SIGNAL
                }
                if (value == RETRY_SIGNAL) continue
                if (value <= 0 || value > ACK_BUF_SIZE) {
                    return failed()
                }
                // Process all bytes received in this read call.
                // ACK takes priority: if ACK appears before SHUTDOWN in the buffer,
                // we honour the ACK (let the tracee continue) and trigger shutdown
                // only after CONTINUE has been sent to the kernel.
                return when (val outcome = ProfilerAckOutcome.decode(ByteArray(value.toInt()) { ackBuf.get(ValueLayout.JAVA_BYTE, it.toLong()) })) {
                    is ProfilerAckOutcome.Acknowledged -> acknowledged(if (outcome.shutdown) "Parent Command (deferred, ACK took priority)" else null)
                    ProfilerAckOutcome.PassThrough -> passedThrough()
                    is ProfilerAckOutcome.Rejected -> {
                        if (outcome.reason == ProfilerHandshakeFailure.Shutdown) onShutdown("Parent Command during notification")
                        failed(outcome.reason)
                    }
                }
            }
            return failed()
        }

        private companion object {
            private const val RETRY_SIGNAL = -1L
            private const val INTERNAL_ERROR_SIGNAL = -2L
            private const val MAX_ATTEMPTS = 8
        }
    }

    /** Handshake successful, ready to send SECCOMP_USER_NOTIF_FLAG_CONTINUE. */
    class Success(
        override val notifId: Long,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        val deferredShutdownReason: String? = null,
    ) : HandshakeSession()

    /** Handshake failed; the session offers one error reply and terminates its listener. */
    class Failed(
        override val notifId: Long,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        val reason: ProfilerHandshakeFailure = ProfilerHandshakeFailure.Transport,
    ) : HandshakeSession()

    /** Handshake received pass-through command, ready to send SECCOMP_USER_NOTIF_FLAG_CONTINUE. */
    class PassedThrough(
        override val notifId: Long,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    ) : HandshakeSession() {
        fun acknowledged() = Success(notifId, listenerFd)
    }
}
