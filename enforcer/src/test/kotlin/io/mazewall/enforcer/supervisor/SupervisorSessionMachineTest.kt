package io.mazewall.enforcer.supervisor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupervisorSessionMachineTest {
    @Test
    fun `JVM verdict resolver rejects a response for another notification`() {
        val arch = io.mazewall.core.Arch
            .current()

        assertEquals(
            null,
            SupervisorJvmVerdictResolver.route(17, arch.open, arch, 18, 1, 0),
        )
    }

    @Test
    fun `JVM verdict resolver maps an allowed open response to descriptor injection`() {
        val arch = io.mazewall.core.Arch
            .current()

        assertEquals(
            SupervisorRoute.InjectFd,
            SupervisorJvmVerdictResolver.route(17, arch.open, arch, 17, 1, 0),
        )
    }

    @Test
    fun `ask JVM path yields one request then one terminal effect`() {
        val resolving = SupervisorSessionMachine.evaluate(
            SupervisorSessionState.AwaitingNotification,
            SupervisorSessionEvent.NotificationReceived,
        )
        assertEquals(SupervisorSessionEffect.ResolvePath, resolving.effect)

        val waiting = SupervisorSessionMachine.evaluate(
            resolving.state,
            SupervisorSessionEvent.PathResolved(SupervisorRoute.AskJvm),
        )
        assertEquals(SupervisorSessionEffect.RequestJvmVerdict, waiting.effect)

        val replied = SupervisorSessionMachine.evaluate(
            waiting.state,
            SupervisorSessionEvent.JvmVerdictReceived(SupervisorRoute.Continue),
        )
        assertEquals(
            SupervisorSessionEffect.ExecuteRoute(SupervisorRoute.Continue),
            replied.effect,
        )
        assertEquals(SupervisorSessionState.Replied, replied.state)
    }

    @Test
    fun `failure always produces the single fail closed reply`() {
        val transition = SupervisorSessionMachine.evaluate(
            SupervisorSessionState.ResolvingPath,
            SupervisorSessionEvent.Failed,
        )

        assertEquals(SupervisorSessionState.Replied, transition.state)
        assertEquals(SupervisorSessionEffect.Abort(), transition.effect)
    }
}
