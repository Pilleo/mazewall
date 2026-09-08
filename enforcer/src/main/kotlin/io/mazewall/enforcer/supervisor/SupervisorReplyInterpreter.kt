package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.memory.ManagedSegment

/** The only interpreter for terminal non-injection supervisor replies. */
internal class SupervisorReplyInterpreter(
    private val sender: SupervisorResponseSender,
) {
    sealed interface Decision {
        data object Continue : Decision

        data class Abort(
            val errno: Int,
        ) : Decision
    }

    fun execute(
        id: Long,
        response: ManagedSegment,
        decision: Decision,
    ): Boolean =
        try {
            when (decision) {
                Decision.Continue -> sender.continueNotification(id, response)
                is Decision.Abort -> sender.abortNotification(id, decision.errno, response)
            }
            true
        } catch (_: RuntimeException) {
            false
        }
}
