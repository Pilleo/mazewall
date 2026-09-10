package io.mazewall.enforcer.supervisor

import io.mazewall.core.NewFdFlags
import io.mazewall.core.Tid
import io.mazewall.core.testAcquisition
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeByte
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SupervisorReplyInterpreterMatrixTest {
    @Test
    fun `helper overload error has only one interrupted terminal attempt`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.replyResult = SupervisorBoundaryFixture.failure(4)
        assertInstanceOf(ReplyExecutionResult.ReplyFailed::class.java, execute(fixture, SupervisorReplyEffect.Error(11)))
        assertEquals(listOf(KernelReply(42, -11, 0, false)), fixture.replies)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 13])
    fun `secure exec continues only after nonterminal injection and affirmative rewrite ACK`(replyErrno: Int) {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.memory.onRead = { _, buffer, size ->
            assertEquals(1L, size)
            buffer.writeByte(0, 1)
            SupervisorBoundaryFixture.success(1)
        }
        if (replyErrno != 0) fixture.replyResult = SupervisorBoundaryFixture.failure(replyErrno)
        val effect = SupervisorReplyEffect.SecureExecAndReply(SecureExecRequest(Tid(1), 4096, 8192, 322), SupervisorDescriptorResult.Opened(opened(fixture)))
        val result = execute(fixture, effect)
        if (replyErrno == 0) {
            assertEquals(ReplyExecutionResult.ReplySucceeded, result)
        } else {
            assertInstanceOf(ReplyExecutionResult.ReplyFailed::class.java, result)
        }
        assertEquals(listOf(KernelReply(42, 0, 1, false)), fixture.replies)
        assertEquals(listOf(51002), fixture.closed)
        assertEquals(1, fixture.writeCount)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 13])
    fun `failed nonterminal exec injection aborts once and closes the acquired executable`(errno: Int) {
        val fixture = SupervisorBoundaryFixture()
        fixture.injectionResult = SupervisorBoundaryFixture.failure(errno)
        val effect = SupervisorReplyEffect.SecureExecAndReply(SecureExecRequest(Tid(1), 4096, 8192, 322), SupervisorDescriptorResult.Opened(opened(fixture)))
        assertEquals(ReplyExecutionResult.ReplySucceeded, execute(fixture, effect))
        assertEquals(listOf(KernelReply(42, -1, 0, false)), fixture.replies)
        assertEquals(listOf(51002), fixture.closed)
        assertEquals(1, fixture.writeCount)
    }

    @Test
    fun `invalid descriptor result cannot confirm an injection reply`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.injectionResult = SupervisorBoundaryFixture.success(-1)
        val result = execute(fixture, SupervisorReplyEffect.AddFdAndReply(opened(fixture), NewFdFlags.NONE))
        assertInstanceOf(ReplyExecutionResult.ReplyFailed::class.java, result)
        assertEquals(1, fixture.replies.size)
        assertEquals(listOf(51002), fixture.closed)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 13])
    fun `success is reported only after the kernel confirms a normal reply`(errno: Int) {
        for (effect in listOf(SupervisorReplyEffect.Continue, SupervisorReplyEffect.Error(13))) {
            val fixture = SupervisorBoundaryFixture()
            if (errno != 0) fixture.replyResult = SupervisorBoundaryFixture.failure(errno)
            val result = execute(fixture, effect)
            if (errno == 0) {
                assertEquals(ReplyExecutionResult.ReplySucceeded, result)
            } else {
                assertInstanceOf(ReplyExecutionResult.ReplyFailed::class.java, result)
            }
            assertEquals(1, fixture.replies.size)
            assertEquals(if (effect is SupervisorReplyEffect.Error) -13 else 0, fixture.replies.single().error)
            assertEquals(if (effect is SupervisorReplyEffect.Error) 0 else 1, fixture.replies.single().flags)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 13])
    fun `atomic ADDFD SEND failure never becomes a second reply and always closes its source`(errno: Int) {
        val fixture = SupervisorBoundaryFixture()
        if (errno != 0) fixture.injectionResult = SupervisorBoundaryFixture.failure(errno)
        val result = execute(fixture, SupervisorReplyEffect.AddFdAndReply(opened(fixture), NewFdFlags.NONE))
        if (errno == 0) {
            assertEquals(ReplyExecutionResult.ReplySucceeded, result)
        } else {
            assertInstanceOf(ReplyExecutionResult.ReplyFailed::class.java, result)
        }
        assertEquals(listOf(KernelReply(42, 0, 0, true)), fixture.replies)
        assertEquals(listOf(51002), fixture.closed)
    }

    @Test
    fun `post-reply cleanup failure cannot trigger another reply`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.fileSystem.onClose = {
            fixture.closed += it.value
            SupervisorBoundaryFixture.failure(5)
        }
        val result = execute(fixture, SupervisorReplyEffect.AddFdAndReply(opened(fixture), NewFdFlags.NONE))
        assertInstanceOf(ReplyExecutionResult.ReplyFailed::class.java, result)
        assertEquals(1, fixture.replies.size)
        assertEquals(listOf(51002), fixture.closed)
    }

    @Test
    fun `native exception is a terminal failure with no denial retry`() {
        val fixture = SupervisorBoundaryFixture()
        var attempts = 0
        fixture.engine.onIoctl = { _, _, _ ->
            attempts++
            throw IllegalStateException("native boundary failure")
        }
        assertInstanceOf(ReplyExecutionResult.ReplyFailed::class.java, execute(fixture, SupervisorReplyEffect.Continue))
        assertEquals(1, attempts)
    }

    @Test
    fun `protocol violation recycles without a kernel operation`() {
        val fixture = SupervisorBoundaryFixture()
        assertInstanceOf(ReplyExecutionResult.ReplyFailed::class.java, execute(fixture, SupervisorReplyEffect.Recycle("invalid transition")))
        assertEquals(0, fixture.replies.size)
    }

    private fun execute(
        fixture: SupervisorBoundaryFixture,
        effect: SupervisorReplyEffect,
    ): ReplyExecutionResult =
        NativeArena.ofConfined().use { arena ->
            with(arena) { interpreter(fixture).execute(effect, SupervisorReplyContext(42, fixture.context(arena).response)) }
        }

    private fun opened(fixture: SupervisorBoundaryFixture): SupervisorOwnedFd =
        (SupervisorDescriptorResult.from(SupervisorBoundaryFixture.success(51002).testAcquisition(), fixture.engine) as SupervisorDescriptorResult.Opened).descriptor

    private fun interpreter(fixture: SupervisorBoundaryFixture) =
        SupervisorReplyInterpreter(fixture.engine, fixture.listener, SupervisorExecRewriteExchange(fixture.engine, fixture.socket), TraceeDescriptorImporter(fixture.engine) { it }, { 12288 })
}
