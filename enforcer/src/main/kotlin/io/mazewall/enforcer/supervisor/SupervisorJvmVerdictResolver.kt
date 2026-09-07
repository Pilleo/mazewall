package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.SyscallNumber

/** Pure validation and route selection for one JVM verdict frame. */
internal object SupervisorJvmVerdictResolver {
    fun route(
        expectedId: Long,
        nr: Int,
        arch: Arch,
        responseId: Long,
        decision: Int,
        errorNr: Int,
    ): SupervisorRoute? {
        if (responseId != expectedId) return null
        val kind = SupervisorNotificationMachine.classify(SyscallNumber(nr), arch)
        val verdict = SupervisorNotificationMachine.parseJvmVerdict(decision, errorNr)
        return SupervisorNotificationMachine.evaluateJvm(kind, verdict)
    }
}
