package io.mazewall.enforcer.supervisor

import io.mazewall.core.NewFdFlags
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment

/** Terminal intent. Only the reply interpreter may turn one of these values into a kernel reply. */
internal sealed interface SupervisorReplyEffect {
    data object Continue : SupervisorReplyEffect

    data class Value(
        val value: Long,
    ) : SupervisorReplyEffect

    data class Error(
        val errno: Int = NativeConstants.EPERM,
    ) : SupervisorReplyEffect

    data class AddFdAndReply(
        val descriptor: SupervisorOwnedFd,
        val flags: NewFdFlags,
    ) : SupervisorReplyEffect

    class AcceptedSocket(
        val descriptor: SupervisorOwnedFd,
        val request: AcceptRequest,
        address: ByteArray,
        val length: Int,
    ) : SupervisorReplyEffect {
        private val snapshot = address.copyOf()
        val address: ByteArray get() = snapshot.copyOf()
    }

    data class SecureExecAndReply(
        val request: SecureExecRequest,
        val target: SupervisorDescriptorResult,
    ) : SupervisorReplyEffect

    data class Recycle(
        val reason: String,
    ) : SupervisorReplyEffect
}

internal data class SupervisorReplyContext(
    val id: Long,
    val response: ManagedSegment,
)

/** A failed terminal operation is never retried with a different reply. */
internal sealed interface ReplyExecutionResult {
    data object ReplySucceeded : ReplyExecutionResult

    data class ReplyFailed(
        val cause: Throwable,
    ) : ReplyExecutionResult
}
