package io.mazewall.ffi.memory

import io.mazewall.core.Arch
import io.mazewall.core.Tid
import io.mazewall.enforcer.supervisor.SupervisorArgumentExtractor
import io.mazewall.enforcer.supervisor.SupervisorRoute
import io.mazewall.enforcer.supervisor.SupervisorRoutePreparation
import io.mazewall.ffi.Layouts
import io.mazewall.platform.seccomp.SeccompNotifications
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupervisorExecPathInspectionTest {
    @Test
    fun `inspectable exec path remains subject to the session JVM verdict`() {
        val memory = object : TraceeMemoryReader {
            context(arena: NativeArena) override fun readString(
                tid: Tid,
                remoteAddr: Long,
                maxLen: Int,
            ): String {
                assertEquals(77, tid.value)
                assertEquals(4096L, remoteAddr)
                assertEquals(4096, maxLen)
                return "/bin/true"
            }

            context(arena: NativeArena) override fun readBytes(
                tid: Tid,
                remoteAddr: Long,
                len: Int,
            ): ByteArray? = error("exec only reads a pathname")

            context(arena: NativeArena) override fun resolveLink(
                tid: Tid,
                link: String,
            ): String? = error("exec must not acquire path authority")
        }
        NativeArena.ofConfined().use { arena ->
            val notification = arena.allocate(Layouts.SECCOMP_NOTIF)
            notification.writeInt(8, 77)
            notification.writeInt(16, Arch.AMD64.execve)
            notification.writeInt(20, Arch.AMD64.audit)
            notification.writeLong(32, 4096)
            val prepared = with(arena) {
                SupervisorRoutePreparation(memory) { 1 }.prepare(SeccompNotifications.read(notification), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            }
            assertEquals("/bin/true", prepared.context.request.path)
            assertEquals(SupervisorRoute.AskJvm, prepared.route)
        }
    }
}
