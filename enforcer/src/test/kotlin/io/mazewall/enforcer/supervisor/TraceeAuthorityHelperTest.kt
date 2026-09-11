package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.Arch
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.seccomp.SeccompNotification
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class TraceeAuthorityHelperTest {
    @TempDir lateinit var root: Path

    @Test
    fun `helper preserves denied decision and notification identity`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            val target = Files.writeString(root.resolve("target"), "private")
            val request = request(arena.allocateFrom(target.toString()).address())
            val input = input(request, SupervisorRoute.Abort(13, "policy"))
            val output = ByteArrayOutputStream()
            TraceeAuthorityHelper.serve(input, DataOutputStream(output)) { error("denial has no descriptor") }
            DataInputStream(ByteArrayInputStream(output.toByteArray())).use { reply ->
                assertEquals(request.id, reply.readLong())
                val snapshot = TraceeAuthorityProtocol.readSnapshot(reply)
                assertEquals(SupervisorRoute.AskJvm, snapshot.route)
                assertEquals(target.toString(), snapshot.path)
                assertEquals(request.id, reply.readLong())
                assertEquals(SupervisorReplyEffect.Error(13), TraceeAuthorityProtocol.readEffect(reply) { error("unexpected descriptor") })
                assertEquals(-1, reply.read())
            }
        }
        }
    }

    @Test
    fun `helper releases acquired descriptor after successful send and failed send`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            val target = Files.writeString(root.resolve("target"), "private")
            val request = request(arena.allocateFrom(target.toString()).address())
            for (failSend in listOf(false, true)) {
                var transferred: SupervisorOwnedFd? = null
                val failure = IOException("injected transfer failure")
                val send: (SupervisorOwnedFd) -> Unit = { descriptor ->
                    transferred = descriptor
                    assertEquals("private", Files.readString(Path.of("/proc/self/fd/${descriptor.handle.value}")))
                    if (failSend) throw failure
                }
                val action = { TraceeAuthorityHelper.serve(input(request, SupervisorRoute.InjectFd), DataOutputStream(ByteArrayOutputStream()), send) }
                if (failSend) assertSame(failure, assertThrows(IOException::class.java, action)) else action()
                assertNotNull(transferred)
                assertFalse(checkNotNull(transferred).handle.isLiveForIo())
            }
        }
        }
    }

    @Test
    fun `preparation failure cannot be overwritten by a peer allow decision`() {
        val notification = request(1)
        val output = ByteArrayOutputStream()
        TraceeAuthorityHelper.serve(input(notification, SupervisorRoute.InjectFd), DataOutputStream(output)) { error("unreadable path must not open") }
        DataInputStream(ByteArrayInputStream(output.toByteArray())).use { reply ->
            assertEquals(notification.id, reply.readLong())
            assertEquals(14, (TraceeAuthorityProtocol.readSnapshot(reply).route as SupervisorRoute.Abort).errno)
            assertEquals(notification.id, reply.readLong())
            assertEquals(SupervisorReplyEffect.Error(14), TraceeAuthorityProtocol.readEffect(reply) { error("unexpected descriptor") })
        }
    }

    private fun input(
        request: SeccompNotification,
        route: SupervisorRoute,
    ): DataInputStream {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        TraceeAuthorityProtocol.writeRequest(output, request, emptyList())
        TraceeAuthorityProtocol.writeDecision(output, TraceeAuthorityDecision(route, null))
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun request(pointer: Long) =
        SeccompNotification(
        42,
            LinuxNative.process.gettid().value,
            Arch.current().audit,
        Arch.current().openat,
            longArrayOf(-100, pointer, 0, 0, 0, 0),
    )
}
