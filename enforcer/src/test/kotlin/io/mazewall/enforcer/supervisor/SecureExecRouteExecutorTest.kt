package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readString
import io.mazewall.ffi.memory.writeByte
import io.mazewall.testing.getOrFail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SecureExecRouteExecutorTest {
    @Test
    fun `missing path creates a fail-closed rewrite effect without opening anything`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.fileSystem.onOpen = { _, _, _ -> error("uninspectable exec must not open") }
        NativeArena.ofConfined().use { arena ->
            val effect = with(arena) { SecureExecRouteExecutor(fixture.engine).execute(fixture.context(arena, Arch.AMD64.execve), null) }
            val exec = assertInstanceOf(SupervisorReplyEffect.SecureExecAndReply::class.java, effect)
            assertEquals(SupervisorDescriptorResult.Denied(1), exec.target)
            assertEquals(0, fixture.writeCount)
            assertEquals(0, fixture.replies.size)
        }
    }

    @Test
    fun `denied JVM verdict cannot independently authorize or open a secure exec route`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.fileSystem.onOpen = { _, _, _ -> error("route preparation cannot override JVM authorization") }
        val dispatcher = SupervisorRouteDispatcher(SupervisorRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine)))
        NativeArena.ofConfined().use { arena ->
            val effect = with(arena) { dispatcher.execute(SupervisorRoute.Abort(13, "session authorization consumed"), fixture.context(arena, Arch.AMD64.execve), "/bin/true") }
            assertEquals(SupervisorReplyEffect.Error(13), effect.getOrFail("denied route"))
        }
        assertEquals(0, fixture.writeCount)
        assertEquals(0, fixture.replies.size)
    }

    @Test
    fun `JVM-supplied target is opened with O_PATH and denied when rewrite ACK is false`() {
        val fixture = SupervisorBoundaryFixture()
        var opened: String? = null
        val writes = mutableListOf<ByteArray>()
        fixture.engine.fileSystem.onOpen = { path, flags, _ ->
            opened = path.readString(0)
            // Linux O_PATH | O_CLOEXEC, independently of the production constants.
            assertEquals(2621440, flags.value)
            SupervisorBoundaryFixture.success(51002)
        }
        fixture.engine.memory.onWrite = { _, buffer, size ->
            writes += ByteArray(size.toInt()).also { ManagedSegment.copy(buffer, 0, it, 0, it.size) }
            SupervisorBoundaryFixture.success(size)
        }
        NativeArena.ofConfined().use { arena ->
            val context = fixture.context(arena, Arch.AMD64.execve)
            context.request.header.args[1] = 4096
            context.request.header.args[2] = 8192
            val effect = with(arena) { SecureExecRouteExecutor(fixture.engine).execute(context, "/bin/true") }
            assertEquals("/bin/true", opened)
            assertEquals(0, fixture.replies.size)
            val interpreter = SupervisorReplyInterpreter(
                fixture.engine,
                fixture.listener,
                SupervisorExecRewriteExchange(fixture.engine, fixture.socket),
                TraceeDescriptorImporter(fixture.engine) { it },
                { 12288 },
            )
            assertEquals(ReplyExecutionResult.ReplySucceeded, with(arena) { interpreter.execute(effect, SupervisorReplyContext(42, context.response)) })
        }
        assertEquals(listOf(KernelReply(42, -1, 0, false)), fixture.replies)
        assertEquals(listOf(51005, 51006, 51002), fixture.closed)
        val words = java.nio.ByteBuffer
            .wrap(writes.single())
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        assertEquals(listOf(61L, 12288L, 4096L, 8192L, 322L, 1L), List(6) { words.long })
    }
}
