package io.mazewall.profiler.tierE.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Replays committed wire transcripts through both the parser and session machine. */
class ControlProtocolFixtureTest {
    @Serializable
    private data class Fixture(
        val name: String,
        val protocolVersion: Int = TIER_E_CONTROL_PROTOCOL_VERSION,
        val steps: List<Step>,
    )

    @Serializable
    private data class Step(
        val wire: String,
        val reply: String,
        val state: String,
        val event: String? = null,
    )

    @Test
    fun `committed protocol transcripts remain byte identical`() {
        FIXTURES.forEach { resource ->
            val fixture = Json.decodeFromString<Fixture>(
                javaClass
                    .getResourceAsStream("/protocol/$resource")!!
                    .readBytes()
                    .toString(StandardCharsets.UTF_8),
            )
            assertEquals(TIER_E_CONTROL_PROTOCOL_VERSION, fixture.protocolVersion, fixture.name)
            var state: ControlSessionState = ControlSessionState.Idle
            fixture.steps.forEachIndexed { index, step ->
                val parsed = parseControlCommand(step.wire)
                val reply = when (parsed) {
                    is Either.Right -> {
                        val transition = ControlSessionMachine.evaluate(
                            state,
                            ControlSessionEvent.SyntaxError(parsed.value),
                        )
                        state = transition.state
                        assertIs<ControlSessionEffect.SendReply>(transition.effect).reply.render()
                    }
                    is Either.Left -> evaluateCommand(parsed.value, state, step.event).also { state = it.first }.second
                }
                assertEquals(step.reply, reply, "${fixture.name} step $index")
                assertEquals(step.state, state.label(), "${fixture.name} step $index")
            }
        }
    }

    private fun evaluateCommand(
        command: ControlCommand,
        initial: ControlSessionState,
        event: String?,
    ): Pair<ControlSessionState, String> {
        val transition = ControlSessionMachine.evaluate(initial, ControlSessionEvent.CommandReceived(command))
        return when (val effect = transition.effect) {
            is ControlSessionEffect.SendReply -> transition.state to effect.reply.render()
            is ControlSessionEffect.RespondStatus ->
                transition.state to ControlReply
                .ok(
                if (effect.attachedSession == null) "engine=idle" else "engine=active queued=0 lost=0",
            ).render()
            is ControlSessionEffect.PerformAttach -> {
                val post = when (event) {
                    null, "success" -> ControlSessionEvent.AttachSucceeded(
                        AttachedSessionInfo(effect.command.pid, effect.command.sessionTag),
                    )
                    else -> ControlSessionEvent.AttachFailed(event)
                }
                val completed = ControlSessionMachine.evaluate(transition.state, post)
                completed.state to assertIs<ControlSessionEffect.SendReply>(completed.effect).reply.render()
            }
            is ControlSessionEffect.CloseSessionAndReply -> transition.state to effect.reply.render()
            is ControlSessionEffect.CloseAndShutdown -> transition.state to ControlReply.ok("shutting down").render()
        }
    }

    private fun ControlSessionState.label(): String =
        when (this) {
        ControlSessionState.Idle -> "Idle"
        is ControlSessionState.Attaching -> "Attaching"
        is ControlSessionState.Attached -> "Attached"
        ControlSessionState.Terminated -> "Terminated"
    }

    private companion object {
        val FIXTURES = listOf("lifecycle.json", "failure-paths.json")
    }
}
