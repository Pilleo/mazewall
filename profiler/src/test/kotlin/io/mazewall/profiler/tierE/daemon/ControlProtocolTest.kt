package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.attribution.TierEEmissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ControlProtocolTest {
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
}
