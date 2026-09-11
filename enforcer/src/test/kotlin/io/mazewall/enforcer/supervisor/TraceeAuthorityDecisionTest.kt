package io.mazewall.enforcer.supervisor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TraceeAuthorityDecisionTest {
    @Test
    fun `denied preparation cannot gain execution authority from a later decision`() {
        val denied = SupervisorRoute.Abort(13, "permission")
        for (decision in listOf(SupervisorRoute.InjectFd, SupervisorRoute.SecureExec, SupervisorRoute.Continue)) {
            assertEquals(denied, TraceeAuthorityDecisionMachine.evaluate(denied, decision))
        }
    }

    @Test
    fun `only prepared JVM request consumes a JVM decision and repeated request fails closed`() {
        assertEquals(SupervisorRoute.SecureExec, TraceeAuthorityDecisionMachine.evaluate(SupervisorRoute.AskJvm, SupervisorRoute.SecureExec))
        assertEquals(SupervisorRoute.InjectFd, TraceeAuthorityDecisionMachine.evaluate(SupervisorRoute.InjectFd, SupervisorRoute.Continue))
        assertEquals(1, assertInstanceOf(SupervisorRoute.Abort::class.java, TraceeAuthorityDecisionMachine.evaluate(SupervisorRoute.AskJvm, SupervisorRoute.AskJvm)).errno)
    }
}
