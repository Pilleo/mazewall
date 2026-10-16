package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.testing.awaitOrFail
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SupervisorPostReplyFailureTest {
    @Test
    fun `unresolved synchronous path is denied before descriptor allocation`() {
        val fixture = SupervisorBoundaryFixture()
        val fatal = LinkageError("fatal opened-source cleanup")
        fixture.engine.fileSystem.onClose = { fd ->
            fixture.closed += fd.value
            if (fd.value == 51002) throw fatal
            SupervisorBoundaryFixture.success(0)
        }
        val handler = fixture.handler()
        NativeArena.ofConfined().use { arena ->
            val handled = handler.processNotification(fixture.notification(arena, Arch.AMD64.open), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            val finalAction = with(arena) {
                handler.handleActiveListener(arena.allocate(Layouts.POLLFD, 2), arena.allocate(Layouts.SECCOMP_NOTIF), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            }
            assertAll(
                { assertTrue(handled) },
                { assertEquals(LoopAction.Continue, finalAction) },
                { assertEquals(listOf(KernelReply(42, -1, 0, false)), fixture.replies) },
                { assertEquals(emptyList<Long>(), fixture.closed) },
                { assertEquals(0, fixture.writeCount) },
            )
        }
    }
}
