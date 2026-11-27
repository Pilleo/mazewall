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
import io.mazewall.profiler.internal.engine.ACK_BUF_SIZE
import io.mazewall.profiler.internal.engine.POLLFD_REVENTS_OFF
import io.mazewall.profiler.internal.engine.POLL_ACK_TIMEOUT_MS
import io.mazewall.profiler.internal.engine.ProfilerAckOutcome
import io.mazewall.profiler.internal.engine.ProfilerHandshakeFailure
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

            var passThroughRequested = false
            var attempts = 0
            while (attempts++ < MAX_ATTEMPTS) {
                when (val outcome = pollAck(socketFd, ioOps, pollFd, ackBuf, deadline) ?: continue) {
                    ProfilerAckOutcome.PassThroughRequested -> passThroughRequested = true
                    is ProfilerAckOutcome.Acknowledged -> return when (outcome.action.withPendingPassThrough(passThroughRequested)) {
                        ProfilerAckOutcome.Action.Continue -> acknowledged()
                        ProfilerAckOutcome.Action.PassThrough -> passedThrough()
                        ProfilerAckOutcome.Action.Shutdown -> acknowledged("Parent Command (deferred, ACK took priority)")
                    }
                    is ProfilerAckOutcome.Rejected -> {
                        if (outcome.reason == ProfilerHandshakeFailure.Shutdown) onShutdown("Parent Command during notification")
                        return failed(outcome.reason)
                    }
                }
            }
            return failed(ProfilerHandshakeFailure.Timeout)
        }

        /** Null requests a new poll after EINTR. */
        private fun pollAck(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            ioOps: NativeIoOperations,
            pollFd: MemorySegment,
            ackBuf: MemorySegment,
            deadline: Deadline,
        ): ProfilerAckOutcome? {
            val remainingMillis = deadline.remainingMillis()
            if (remainingMillis <= 0) return ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.Timeout)
            return when (val ready = ioOps.raw.poll(ConfinedSegment(pollFd), 1L, remainingMillis)) {
                is LinuxNative.SyscallResult.Error ->
                    if (ready.errno == NativeConstants.EINTR) null else ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.Transport)
                is LinuxNative.SyscallResult.Success -> {
                    val revents = pollFd.get(ValueLayout.JAVA_SHORT, POLLFD_REVENTS_OFF)
                    when {
                        ready.value == 0L -> ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.Timeout)
                        (revents.toInt() and NativeConstants.POLLIN.toInt()) == 0 -> ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.Transport)
                        else -> readAck(socketFd, ioOps, ackBuf, deadline)
                    }
                }
            }
        }

        private fun readAck(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            ioOps: NativeIoOperations,
            ackBuf: MemorySegment,
            deadline: Deadline,
        ): ProfilerAckOutcome {
            var attempts = 0
            while (attempts++ < MAX_ATTEMPTS) {
                if (deadline.remainingMillis() <= 0) return ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.Timeout)
                val result = ioOps.read(socketFd, ackBuf, ACK_BUF_SIZE)
                if (result is LinuxNative.SyscallResult.Error && result.errno == NativeConstants.EINTR) continue
                val size = (result as? LinuxNative.SyscallResult.Success)?.value
                return when {
                    size == null || size <= 0 || size > ACK_BUF_SIZE -> ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.Transport)
                    deadline.remainingMillis() <= 0 -> ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.Timeout)
                    else -> ProfilerAckOutcome.decode(ByteArray(size.toInt()) { ackBuf.get(ValueLayout.JAVA_BYTE, it.toLong()) })
                }
            }
            return ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.Transport)
        }

        private companion object {
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

    /** The event was acknowledged and the parent requested pass-through. */
    class PassedThrough(
        override val notifId: Long,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    ) : HandshakeSession() {
        fun acknowledged() = Success(notifId, listenerFd)
    }
}
