package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupervisorSessionMachineTest {
    @Test
    fun `all bounded event traces require a successful reply before replied`() {
        val events = listOf(
            SupervisorSessionEvent.NotificationReceived,
            SupervisorSessionEvent.PathResolved(SupervisorRoute.Continue),
            SupervisorSessionEvent.JvmRequestSent,
            SupervisorSessionEvent.JvmRequestFailed,
            SupervisorSessionEvent.JvmVerdictReceived(SupervisorRoute.Continue),
            SupervisorSessionEvent.ReplySucceeded,
            SupervisorSessionEvent.ReplyFailed,
        )

        fun explore(
            state: SupervisorSessionState,
            successfulReply: Boolean,
            depth: Int,
        ) {
            if (state is SupervisorSessionState.Replied) {
                assertTrue(successfulReply, "Replied must be reachable only after ReplySucceeded")
            }
            if (depth == 4) return
            events.forEach { event ->
                val transition = SupervisorSessionMachine.evaluate(state, event)
                explore(transition.state, successfulReply || event is SupervisorSessionEvent.ReplySucceeded, depth + 1)
            }
        }

        explore(SupervisorSessionState.AwaitingNotification, successfulReply = false, depth = 0)
    }

    @Test
    fun `every state and event pair has an explicit transition`() {
        val states = listOf(
            SupervisorSessionState.AwaitingNotification,
            SupervisorSessionState.ResolvingPath,
            SupervisorSessionState.SendingJvmRequest,
            SupervisorSessionState.AwaitingJvmVerdict,
            SupervisorSessionState.ExecutingReply,
            SupervisorSessionState.Replied,
            SupervisorSessionState.Terminated,
        )
        val events = listOf(
            SupervisorSessionEvent.NotificationReceived,
            SupervisorSessionEvent.PathResolved(SupervisorRoute.Continue),
            SupervisorSessionEvent.JvmVerdictReceived(SupervisorRoute.Abort(1, "test")),
            SupervisorSessionEvent.JvmRequestSent,
            SupervisorSessionEvent.JvmRequestFailed,
            SupervisorSessionEvent.ReplySucceeded,
            SupervisorSessionEvent.ReplyFailed,
            SupervisorSessionEvent.Failed,
        )

        for (state in states) {
            for (event in events) {
                val transition = SupervisorSessionMachine.evaluate(state, event)
                if (state == SupervisorSessionState.Replied || state == SupervisorSessionState.Terminated) {
                    assertEquals(state, transition.state)
                    assertEquals(SupervisorSessionEffect.PostTerminalObservation, transition.effect)
                } else {
                    org.junit.jupiter.api.Assertions
                        .assertNotNull(transition.state)
                }
            }
        }
    }

    @Test
    fun `JVM verdict resolver rejects a response for another notification`() {
        val arch = io.mazewall.platform.syscall.Arch
            .current()

        assertEquals(
            null,
            SupervisorJvmVerdictResolver.route(17, arch.open, arch, 18, 1, 0),
        )
    }

    @Test
    fun `JVM verdict resolver maps an allowed open response to descriptor injection`() {
        val arch = io.mazewall.platform.syscall.Arch
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

    @Test
    fun `failed kernel reply terminates without manufacturing a second reply`() {
        val failed = SupervisorSessionMachine.evaluate(
            SupervisorSessionState.ExecutingReply,
            SupervisorSessionEvent.ReplyFailed,
        )

        assertEquals(SupervisorSessionState.Terminated, failed.state)
        assertEquals(null, failed.effect)
    }

    @Test
    fun `out of order verdict is a protocol violation rather than a silent no-op`() {
        val violation = SupervisorSessionMachine.evaluate(
            SupervisorSessionState.AwaitingNotification,
            SupervisorSessionEvent.JvmVerdictReceived(SupervisorRoute.Continue),
        )

        assertEquals(SupervisorSessionState.Terminated, violation.state)
        assertEquals(
            SupervisorSessionEffect.ProtocolViolation(
                SupervisorSessionState.AwaitingNotification,
                SupervisorSessionEvent.JvmVerdictReceived(SupervisorRoute.Continue),
            ),
            violation.effect,
        )
    }

    @Test
    fun `post terminal observations are explicit and do not reply twice`() {
        val observed = SupervisorSessionMachine.evaluate(
            SupervisorSessionState.Replied,
            SupervisorSessionEvent.NotificationReceived,
        )

        assertEquals(SupervisorSessionState.Replied, observed.state)
        assertEquals(SupervisorSessionEffect.PostTerminalObservation, observed.effect)
    }
}
