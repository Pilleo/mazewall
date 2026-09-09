package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SocketRouteExecutorTest {
    @Test
    fun `accepted descriptor closes when releasing the imported listener fails`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.fileSystem.onClose = { fd ->
            fixture.closed += fd.value
            if (fd.value == 51006) SupervisorBoundaryFixture.failure(13) else SupervisorBoundaryFixture.success(0)
        }
        NativeArena.ofConfined().use { arena ->
            assertThrows(IllegalStateException::class.java) {
                with(arena) { sockets(fixture).accept(AcceptRequest(Tid(77), 55, 0, 0, 0)) }
            }
        }
        assertEquals(listOf(51005, 51006, 51004), fixture.closed)
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 32770])
    fun `connect imports the original socket and returns zero without injection`(domain: Int) {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.networking.onSocket = { _, _, _ -> error("connect must not create a replacement socket") }
        fixture.engine.networking.onConnect = { fd, _, _ ->
            assertEquals(51006, fd.value)
            SupervisorBoundaryFixture.success(0)
        }
        NativeArena.ofConfined().use { arena ->
            val address = byteArrayOf(domain.toByte(), (domain shr 8).toByte())
            val effect = with(arena) { sockets(fixture).connect(Tid(77), 55, address) }
            assertEquals(SupervisorReplyEffect.Value(0), effect)
            assertEquals(0, fixture.replies.size)
            assertEquals(listOf(51005, 51006), fixture.closed)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 13, 11, 115, 110])
    fun `connect permission denial releases its own socket and cannot continue`(errno: Int) {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.networking.connectResult = SupervisorBoundaryFixture.failure(errno)
        NativeArena.ofConfined().use { arena ->
            assertEquals(SupervisorReplyEffect.Error(errno), with(arena) { sockets(fixture).connect(Tid(77), 55, byteArrayOf(2, 0)) })
        }
        assertEquals(listOf(51005, 51006), fixture.closed)
        assertEquals(0, fixture.replies.size)
    }

    @Test
    fun `accept preserves CLOEXEC flags and closes only imported intermediates`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.networking.onAccept4 = { fd, _, _, flags ->
            assertEquals(51006, fd.value)
            assertEquals(524288, flags)
            SupervisorBoundaryFixture.success(51004)
        }
        NativeArena.ofConfined().use { arena ->
            val effect = with(arena) { sockets(fixture).accept(AcceptRequest(Tid(77), 55, 0, 0, 524288)) } as SupervisorReplyEffect.AddFdAndReply
            assertEquals(524288, effect.flags.value)
            assertEquals(listOf(51005, 51006), fixture.closed)
            effect.descriptor.close()
            assertEquals(listOf(51005, 51006, 51004), fixture.closed)
        }
        assertEquals(0, fixture.replies.size)
    }

    @Test
    fun `accept failure closes the pidfd and duplicate without adopting a failed result`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.networking.acceptResult = SupervisorBoundaryFixture.failure(13)
        NativeArena.ofConfined().use { arena ->
            assertEquals(SupervisorReplyEffect.Error(13), with(arena) { sockets(fixture).accept(AcceptRequest(Tid(77), 55, 0, 0, 0)) })
        }
        assertEquals(listOf(51005, 51006), fixture.closed)
    }

    private fun sockets(fixture: SupervisorBoundaryFixture) = SocketRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it })
}
