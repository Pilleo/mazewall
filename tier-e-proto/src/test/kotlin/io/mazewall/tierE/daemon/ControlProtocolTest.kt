package io.mazewall.tierE.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class ControlProtocolTest {

    @Test
    fun `parses attach with mode and path`() {
        val result = parseControlCommand("ATTACH 4242 usdt /work/build/lib.so")
        val cmd = assertIs<Either.Left<ControlCommand, ControlReply>>(result).value
        cmd as ControlCommand.Attach
        assertEquals(4242, cmd.pid)
        assertEquals(AttachMode.USDT, cmd.mode)
        assertEquals("/work/build/lib.so", cmd.markerPath)
    }

    @Test
    fun `attach requires exactly four tokens`() {
        val reply = assertIs<Either.Right<ControlCommand, ControlReply>>(
            parseControlCommand("ATTACH 1 uprobe"),
        ).value
        assertTrue(reply.text.contains("USAGE"))
    }

    @Test
    fun `non-positive pid rejected`() {
        val reply = assertIs<Either.Right<ControlCommand, ControlReply>>(
            parseControlCommand("ATTACH 0 uprobe /x"),
        ).value
        assertEquals("BAD_PID", reply.text)
    }

    @Test
    fun `unknown mode rejected`() {
        val reply = assertIs<Either.Right<ControlCommand, ControlReply>>(
            parseControlCommand("ATTACH 5 kprobe /x"),
        ).value
        assertEquals("BAD_MODE", reply.text)
    }

    @Test
    fun `no-arg commands parse and reject trailing junk`() {
        assertIs<Either.Left<ControlCommand, ControlReply>>(parseControlCommand("detach"))
        assertIs<Either.Left<ControlCommand, ControlReply>>(parseControlCommand("  STATUS  "))
        assertIs<Either.Left<ControlCommand, ControlReply>>(parseControlCommand("SHUTDOWN"))
        val bad = assertIs<Either.Right<ControlCommand, ControlReply>>(
            parseControlCommand("DETACH now"),
        ).value
        assertTrue(bad.text.contains("USAGE"))
    }

    @Test
    fun `unknown verb gets usage error`() {
        val reply = assertIs<Either.Right<ControlCommand, ControlReply>>(
            parseControlCommand("HELLO"),
        ).value
        assertTrue(reply.text.contains("USAGE"))
        assertTrue(reply.render().startsWith("ERR "))
    }
}
