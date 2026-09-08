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
    fun `JVM route is replied only after a confirmed reply effect`() {
        val resolving = SupervisorSessionMachine.evaluate(
            SupervisorSessionState.AwaitingNotification,
            SupervisorSessionEvent.NotificationReceived,
        )
        assertEquals(SupervisorSessionEffect.ResolvePath, resolving.effect)

        val sending = SupervisorSessionMachine.evaluate(
            resolving.state,
            SupervisorSessionEvent.PathResolved(SupervisorRoute.AskJvm),
        )
        assertEquals(SupervisorSessionState.SendingJvmRequest, sending.state)
        assertEquals(SupervisorSessionEffect.RequestJvmVerdict, sending.effect)

        val waiting = SupervisorSessionMachine.evaluate(
            sending.state,
            SupervisorSessionEvent.JvmRequestSent,
        )
        assertEquals(SupervisorSessionState.AwaitingJvmVerdict, waiting.state)

        val executing = SupervisorSessionMachine.evaluate(
            waiting.state,
            SupervisorSessionEvent.JvmVerdictReceived(SupervisorRoute.Continue),
        )
        assertEquals(
            SupervisorSessionEffect.ExecuteRoute(SupervisorRoute.Continue),
            executing.effect,
        )
        assertEquals(SupervisorSessionState.ExecutingReply, executing.state)

        val replied = SupervisorSessionMachine.evaluate(executing.state, SupervisorSessionEvent.ReplySucceeded)
        assertEquals(SupervisorSessionState.Replied, replied.state)
    }

    @Test
    fun `failure enters reply execution with the single fail closed reply`() {
        val transition = SupervisorSessionMachine.evaluate(
            SupervisorSessionState.ResolvingPath,
            SupervisorSessionEvent.Failed,
        )

        assertEquals(SupervisorSessionState.ExecutingReply, transition.state)
        assertEquals(SupervisorSessionEffect.Abort(), transition.effect)
    }
}
