package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.attribution.TierEEmissionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ControlProtocolTest {
    companion object {
        @JvmStatic
        fun simpleCommands(): Stream<Arguments> =
            Stream.of(
                Arguments.of("detach", "  DETACH  ", ControlCommand.Detach),
                Arguments.of("status", "STATUS", ControlCommand.Status),
                Arguments.of("shutdown", "SHUTDOWN", ControlCommand.Shutdown),
            )

        @JvmStatic
        fun rejectedCommands(): Stream<Arguments> =
            Stream.of(
                Arguments.of("empty", "", "EMPTY_COMMAND"),
                Arguments.of("attach wrong arity", "ATTACH 42 uprobe", "USAGE:"),
                Arguments.of("non-positive pid", "ATTACH 0 uprobe /a 1 1", "BAD_PID"),
                Arguments.of("invalid mode", "ATTACH 42 tracepoint /a 1 1", "BAD_MODE"),
                Arguments.of("malformed marker offset", "ATTACH 42 usdt /a nope 1", "BAD_MARKER_OFFSET"),
                Arguments.of("negative marker offset", "ATTACH 42 usdt /a -1 1", "BAD_MARKER_OFFSET"),
                Arguments.of("malformed session tag", "ATTACH 42 usdt /a 1 nope", "BAD_SESSION_TAG"),
                Arguments.of("zero session tag", "ATTACH 42 usdt /a 1 0", "BAD_SESSION_TAG"),
                Arguments.of("unsupported emission mode", "ATTACH 42 usdt /a 1 1 compact", "BAD_EMISSION_MODE"),
                Arguments.of("detach arguments", "DETACH now", "USAGE:"),
                Arguments.of("status arguments", "STATUS verbose", "USAGE:"),
                Arguments.of("shutdown arguments", "SHUTDOWN now", "USAGE:"),
            )
    }

    @Test
    fun `attach binds marker offset and agent session tag`() {
        val parsed = parseControlCommand("ATTACH 42 uprobe /opt/libagent.so b0aa 4d5a")
        val attach = assertIs<Either.Left<ControlCommand, ControlReply>>(parsed).value
        assertEquals(ControlCommand.Attach(42, AttachMode.UPROBE, "/opt/libagent.so", 0xb0aa, 0x4d5a), attach)
    }

    @Test
    fun `attach defaults to full stream and accepts explicit unique edge coverage`() {
        val defaultAttach = assertIs<Either.Left<ControlCommand, ControlReply>>(
            parseControlCommand("ATTACH 42 uprobe /opt/libagent.so b0aa 4d5a"),
        ).value
        assertEquals(TierEEmissionMode.FULL_STREAM, (defaultAttach as ControlCommand.Attach).emissionMode)

        val coverageAttach = assertIs<Either.Left<ControlCommand, ControlReply>>(
            parseControlCommand("ATTACH 42 uprobe /opt/libagent.so b0aa 4d5a unique-stack-syscall"),
        ).value
        assertEquals(TierEEmissionMode.UNIQUE_STACK_SYSCALL, (coverageAttach as ControlCommand.Attach).emissionMode)
    }

    @Test
    fun `attach rejects zero session tag`() {
        val parsed = parseControlCommand("ATTACH 42 uprobe /opt/libagent.so b0aa 0")
        assertEquals("ERR BAD_SESSION_TAG\n", assertIs<Either.Right<ControlCommand, ControlReply>>(parsed).value.render())
    }

    @Test
    fun `unknown command is represented explicitly before it is rejected`() {
        val command = assertIs<Either.Left<ControlCommand, ControlReply>>(parseControlCommand("PING")).value

        assertEquals(ControlCommand.Unknown("PING"), command)
        assertEquals("ERR USAGE:", requireNotNull(command.rejectionReply()).render().take(10))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("simpleCommands")
    fun `simple control commands parse despite surrounding whitespace`(
        name: String,
        wire: String,
        expected: ControlCommand,
    ) {
        assertEquals(expected, assertIs<Either.Left<ControlCommand, ControlReply>>(parseControlCommand(wire)).value)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedCommands")
    fun `invalid control commands have deterministic error replies`(
        name: String,
        wire: String,
        expectedError: String,
    ) {
        val reply = assertIs<Either.Right<ControlCommand, ControlReply>>(parseControlCommand(wire)).value

        assertEquals(false, reply.ok)
        assertEquals(true, reply.text.startsWith(expectedError))
        assertEquals("ERR ${reply.text}\n", reply.render())
    }
}
