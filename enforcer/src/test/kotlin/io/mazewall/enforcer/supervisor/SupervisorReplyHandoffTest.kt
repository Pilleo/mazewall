package io.mazewall.enforcer.supervisor

import io.mazewall.core.NewFdFlags
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SupervisorReplyHandoffTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `abandoned descriptor-bearing effect closes once and cannot be transferred`(secureExec: Boolean) {
        val fixture = SupervisorBoundaryFixture()
        val handoff = SupervisorReplyHandoff(effect(fixture, secureExec))
        handoff.close()
        handoff.close()
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                assertThrows(IllegalStateException::class.java) { handoff.transferTo(interpreter(fixture), SupervisorReplyContext(42, fixture.context(arena).response)) }
            }
        }
        assertEquals(listOf(51002), fixture.closed)
        assertEquals(emptyList<KernelReply>(), fixture.replies)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `disposal failure stays suppressed on the original preparation failure without retry`(fatal: Boolean) {
        val fixture = SupervisorBoundaryFixture()
        val primary = if (fatal) OutOfMemoryError("reply allocation") else IllegalStateException("reply allocation")
        val cleanup = IllegalStateException("owned cleanup")
        fixture.engine.fileSystem.onClose = { fd ->
            fixture.closed += fd.value
            throw cleanup
        }
        val handoff = SupervisorReplyHandoff(effect(fixture, secureExec = true))
        val escaped = assertThrows(primary.javaClass) { handoff.use { throw primary } }
        handoff.close()
        assertSame(primary, escaped)
        assertEquals(listOf(cleanup), escaped.suppressed.toList())
        assertEquals(listOf(51002), fixture.closed)
        assertEquals(emptyList<KernelReply>(), fixture.replies)
    }

    @Test
    fun `interpreter consumes a transferred effect even when its cleanup fails fatally`() {
        val fixture = SupervisorBoundaryFixture()
        val fatal = OutOfMemoryError("post-reply cleanup")
        fixture.engine.fileSystem.onClose = { fd ->
            fixture.closed += fd.value
            throw fatal
        }
        val handoff = SupervisorReplyHandoff(effect(fixture, secureExec = false))
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val context = SupervisorReplyContext(42, fixture.context(arena).response)
                assertSame(fatal, assertThrows(OutOfMemoryError::class.java) { handoff.use { it.transferTo(interpreter(fixture), context) } })
                assertThrows(IllegalStateException::class.java) { handoff.transferTo(interpreter(fixture), context) }
            }
        }
        handoff.close()
        assertEquals(listOf(51002), fixture.closed)
        assertEquals(listOf(KernelReply(42, 0, 0, true)), fixture.replies)
    }

    @Test
    fun `abandoned non-descriptor effects have no native disposal work`() {
        val fixture = SupervisorBoundaryFixture()
        val effects = listOf(
            SupervisorReplyEffect.Continue,
            SupervisorReplyEffect.Error(13),
            SupervisorReplyEffect.Recycle("invalid transition"),
            SupervisorReplyEffect.SecureExecAndReply(SecureExecRequest(Tid(1), 4096, 8192, 322), SupervisorDescriptorResult.Denied(13)),
        )
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val context = SupervisorReplyContext(42, fixture.context(arena).response)
                effects.forEach { effect ->
                    val handoff = SupervisorReplyHandoff(effect)
                    handoff.close()
                    handoff.close()
                    assertThrows(IllegalStateException::class.java) { handoff.transferTo(interpreter(fixture), context) }
                }
            }
        }
        assertEquals(emptyList<Int>(), fixture.closed)
        assertEquals(emptyList<KernelReply>(), fixture.replies)
    }

    private fun effect(
        fixture: SupervisorBoundaryFixture,
        secureExec: Boolean,
    ): SupervisorReplyEffect {
        val opened = SupervisorDescriptorResult.from(SupervisorBoundaryFixture.success(51002), fixture.engine) as SupervisorDescriptorResult.Opened
        return if (secureExec) {
            SupervisorReplyEffect.SecureExecAndReply(SecureExecRequest(Tid(1), 4096, 8192, 322), opened)
        } else {
            SupervisorReplyEffect.AddFdAndReply(opened.descriptor, NewFdFlags.NONE)
        }
    }

    private fun interpreter(fixture: SupervisorBoundaryFixture) =
        SupervisorReplyInterpreter(fixture.engine, fixture.listener, SupervisorExecRewriteExchange(fixture.engine, fixture.socket), TraceeDescriptorImporter(fixture.engine) { it }, { 12288 })
}
