package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.attribution.TierEEmissionMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ControlSessionMachineTest {
    @Test
    fun `initial state is Idle`() {
        val machine = ControlSessionMachine
        val state = ControlSessionState.Idle

        // Status on Idle produces Status effect and remains Idle
        val transition = machine.evaluate(state, ControlSessionEvent.CommandReceived(ControlCommand.Status))
        assertEquals(ControlSessionState.Idle, transition.state)
        val effect = assertIs<ControlSessionEffect.RespondStatus>(transition.effect)
        assertEquals(null, effect.attachedSession)
    }

    @Test
    fun `attach on Idle transitions to Attaching`() {
        val attachCmd = ControlCommand.Attach(
            pid = 1234,
            mode = AttachMode.UPROBE,
            markerPath = "/opt/agent.so",
            markerOffset = 0x1000L,
            sessionTag = 0x42,
            emissionMode = TierEEmissionMode.FULL_STREAM,
        )

        val transition = ControlSessionMachine.evaluate(
            ControlSessionState.Idle,
            ControlSessionEvent.CommandReceived(attachCmd),
        )
        assertEquals(ControlSessionState.Attaching(attachCmd), transition.state)
        val effect = assertIs<ControlSessionEffect.PerformAttach>(transition.effect)
        assertEquals(attachCmd, effect.command)
        assertEquals(null, effect.previousSession)
    }

    @Test
    fun `attach success transitions to Attached and replies OK`() {
        val attachCmd = ControlCommand.Attach(
            pid = 1234,
            mode = AttachMode.UPROBE,
            markerPath = "/opt/agent.so",
            markerOffset = 0x1000L,
            sessionTag = 0x42,
            emissionMode = TierEEmissionMode.FULL_STREAM,
        )
        val session = AttachedSessionInfo(pid = 1234, sessionTag = 0x42)

        val transition = ControlSessionMachine.evaluate(
            ControlSessionState.Attaching(attachCmd),
            ControlSessionEvent.AttachSucceeded(session),
        )
        assertEquals(ControlSessionState.Attached(session), transition.state)
        val effect = assertIs<ControlSessionEffect.SendReply>(transition.effect)
        assertEquals(ControlReply.ok("attached pid=1234"), effect.reply)
    }

    @Test
    fun `attach failure transitions to Idle and replies ERR`() {
        val attachCmd = ControlCommand.Attach(
            pid = 1234,
            mode = AttachMode.UPROBE,
            markerPath = "/opt/agent.so",
            markerOffset = 0x1000L,
            sessionTag = 0x42,
            emissionMode = TierEEmissionMode.FULL_STREAM,
        )

        val transition = ControlSessionMachine.evaluate(
            ControlSessionState.Attaching(attachCmd),
            ControlSessionEvent.AttachFailed("permission denied"),
        )
        assertEquals(ControlSessionState.Idle, transition.state)
        val effect = assertIs<ControlSessionEffect.SendReply>(transition.effect)
        assertEquals(ControlReply.err("permission denied"), effect.reply)
    }

    @Test
    fun `attach when already Attached cleans up previous session`() {
        val oldSession = AttachedSessionInfo(pid = 1000, sessionTag = 0x1)
        val newAttachCmd = ControlCommand.Attach(
            pid = 2000,
            mode = AttachMode.UPROBE,
            markerPath = "/opt/agent2.so",
            markerOffset = 0x2000L,
            sessionTag = 0x2,
            emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL,
        )

        val transition = ControlSessionMachine.evaluate(
            ControlSessionState.Attached(oldSession),
            ControlSessionEvent.CommandReceived(newAttachCmd),
        )
        assertEquals(ControlSessionState.Attaching(newAttachCmd), transition.state)
        val effect = assertIs<ControlSessionEffect.PerformAttach>(transition.effect)
        assertEquals(newAttachCmd, effect.command)
        assertEquals(oldSession, effect.previousSession)
    }

    @Test
    fun `detach when Attached transitions to Idle and closes session`() {
        val session = AttachedSessionInfo(pid = 1234, sessionTag = 0x42)

        val transition = ControlSessionMachine.evaluate(
            ControlSessionState.Attached(session),
            ControlSessionEvent.CommandReceived(ControlCommand.Detach),
        )
        assertEquals(ControlSessionState.Idle, transition.state)
        val effect = assertIs<ControlSessionEffect.CloseSessionAndReply>(transition.effect)
        assertEquals(session, effect.session)
        assertEquals(ControlReply.ok("detached"), effect.reply)
    }

    @Test
    fun `detach when Idle is a no-op that replies OK detached`() {
        val transition = ControlSessionMachine.evaluate(
            ControlSessionState.Idle,
            ControlSessionEvent.CommandReceived(ControlCommand.Detach),
        )
        assertEquals(ControlSessionState.Idle, transition.state)
        val effect = assertIs<ControlSessionEffect.SendReply>(transition.effect)
        assertEquals(ControlReply.ok("detached"), effect.reply)
    }

    @Test
    fun `status when Attached emits active session status`() {
        val session = AttachedSessionInfo(pid = 1234, sessionTag = 0x42)

        val transition = ControlSessionMachine.evaluate(
            ControlSessionState.Attached(session),
            ControlSessionEvent.CommandReceived(ControlCommand.Status),
        )
        assertEquals(ControlSessionState.Attached(session), transition.state)
        val effect = assertIs<ControlSessionEffect.RespondStatus>(transition.effect)
        assertEquals(session, effect.attachedSession)
    }

    @Test
    fun `shutdown transitions to Terminated and issues CloseAndShutdown`() {
        val session = AttachedSessionInfo(pid = 1234, sessionTag = 0x42)

        val transition = ControlSessionMachine.evaluate(
            ControlSessionState.Attached(session),
            ControlSessionEvent.CommandReceived(ControlCommand.Shutdown),
        )
        assertEquals(ControlSessionState.Terminated, transition.state)
        val effect = assertIs<ControlSessionEffect.CloseAndShutdown>(transition.effect)
        assertEquals(session, effect.session)
    }

    @Test
    fun `rejected command syntax returns error without changing state`() {
        val transition = ControlSessionMachine.evaluate(
            ControlSessionState.Idle,
            ControlSessionEvent.SyntaxError(ControlReply.err("EMPTY_COMMAND")),
        )
        assertEquals(ControlSessionState.Idle, transition.state)
        val effect = assertIs<ControlSessionEffect.SendReply>(transition.effect)
        assertEquals(ControlReply.err("EMPTY_COMMAND"), effect.reply)
    }
}
