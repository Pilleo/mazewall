package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.seccomp.UserNotifReplyResult
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class TraceeAuthorityReplyContractTest {
    @Test
    fun `authorized scalar completion waits for JVM verdict then replies once`() {
        val fixture = SupervisorBoundaryFixture()
        withHelper(fixture, Arch.AMD64.clone, SupervisorRoute.AskJvm, { decision ->
            assertEquals(SupervisorRoute.Continue, decision.route)
            SupervisorReplyEffect.Continue
        }) { terminal ->
            assertTrue(fixture.replies.isEmpty())
            assertEquals(1, fixture.writeCount)
            assertEquals(1, fixture.readCount)
            assertEquals(UserNotifReplyResult.Sent, terminal.reply())
            assertEquals(1, fixture.replies.size)
        }
    }

    @Test
    fun `preparation denial skips JVM and preserves errno`() {
        val fixture = SupervisorBoundaryFixture()
        withHelper(fixture, Arch.AMD64.openat, SupervisorRoute.Abort(13, "unreadable"), { decision ->
            assertEquals(13, (decision.route as SupervisorRoute.Abort).errno)
            SupervisorReplyEffect.Error(13)
        }) { terminal ->
            assertEquals(0, fixture.writeCount)
            assertEquals(UserNotifReplyResult.Sent, terminal.reply())
            assertEquals(-13, fixture.replies.single().error)
        }
    }

    @Test
    fun `mismatched JVM reply terminates before helper execution`() {
        val fixture = SupervisorBoundaryFixture().also { it.responseId = 99 }
        withHelper(fixture, Arch.AMD64.clone, SupervisorRoute.AskJvm, { error("malformed verdict must not reach helper execution") }) { terminal ->
            assertEquals(NotifResult.TERMINATE, terminal.result)
            assertEquals(UserNotifReplyResult.Sent, terminal.reply())
            assertEquals(1, fixture.replies.size)
        }
    }

    @Test
    fun `denied secure exec completes rewrite handshake without injecting descriptor`() {
        val fixture = SupervisorBoundaryFixture()
        withHelper(fixture, Arch.AMD64.execve, SupervisorRoute.AskJvm, { decision ->
            assertEquals(SupervisorRoute.SecureExec, decision.route)
            SupervisorReplyEffect.SecureExecAndReply(SecureExecRequest(Tid(1), 0, 0, Arch.AMD64.execveat.toLong()), SupervisorDescriptorResult.Denied(13), 0)
        }) { terminal ->
            assertEquals(UserNotifReplyResult.Sent, terminal.reply())
            assertEquals(2, fixture.writeCount)
            assertEquals(2, fixture.readCount)
            assertEquals(-13, fixture.replies.single().error)
        }
    }

    private fun withHelper(
        fixture: SupervisorBoundaryFixture,
        nr: Int,
        prepared: SupervisorRoute,
        execute: (TraceeAuthorityDecision) -> SupervisorReplyEffect,
        verify: (NotificationProgress.Ready) -> Unit,
    ) {
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = error("unexpected socket operation")

                override fun executeAuthority(job: TraceeAuthorityJob): SupervisorReplyEffect {
                    job.prepared.complete(TraceeAuthoritySnapshot(123, null, null, prepared))
                    return execute(job.decision.get(5, TimeUnit.SECONDS))
                }

                override fun close() = Unit
            }
        }.use { pool ->
            SupervisorSessionHandler(fixture.socket, fixture.listener, fixture.engine, fixture.sockets, socketHelpers = pool, authorityRoots = emptyList()).use { handler ->
                NativeArena.ofConfined().use { arena ->
                    with(arena) {
                    val work = handler.beginNotification(fixture.notification(arena, nr), arena.allocate(Layouts.SECCOMP_NOTIF_RESP), fixture.listener, fixture.socket, Deadline.afterMillis(30000))
                    work.use {
                        var terminal: NotificationProgress.Ready? = null
                        awaitCondition(description = "authority terminal reply") {
                            terminal = work.poll() as? NotificationProgress.Ready
                            terminal != null
                        }
                        verify(checkNotNull(terminal))
                    }
                    work.close()
                    assertThrows(IllegalStateException::class.java) { work.poll() }
                }
                }
            }
        }
    }
}
