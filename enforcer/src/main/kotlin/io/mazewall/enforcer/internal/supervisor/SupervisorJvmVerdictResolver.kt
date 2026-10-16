package io.mazewall.enforcer.internal.supervisor

import io.mazewall.platform.lifecycle.SyscallNumber
import io.mazewall.platform.syscall.Arch

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
