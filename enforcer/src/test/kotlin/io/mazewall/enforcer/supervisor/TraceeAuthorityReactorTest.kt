package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.Deadline
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TraceeAuthorityReactorTest {
    @Test
    fun `blocked authority preparation yields and cancellation disposes late effect without reply`() {
        val fixture = SupervisorBoundaryFixture()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val opened = SupervisorOwnedFd(TestFileDescriptors.replace<FileDescriptorRole.Generic>(52900), fixture.engine.fileSystem)
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = error("Unexpected socket request")

                override fun executeAuthority(job: TraceeAuthorityJob): SupervisorReplyEffect {
                    started.countDown()
                    assertTrue(stopped.await(5, TimeUnit.SECONDS))
                    return SupervisorReplyEffect.AddFdAndReply(opened, io.mazewall.core.NewFdFlags(0))
                }

                override fun close() {
                    stopped.countDown()
                }
            }
        }.use { pool ->
            SupervisorSessionHandler(fixture.socket, fixture.listener, fixture.engine, fixture.sockets, socketHelpers = pool, authorityRoots = emptyList()).use { handler ->
                NativeArena.ofConfined().use { arena ->
                    with(arena) {
                    val work = handler.beginNotification(
                        fixture.notification(arena, Arch.AMD64.openat),
                        arena.allocate(Layouts.SECCOMP_NOTIF_RESP),
                        fixture.listener,
                        fixture.socket,
                        Deadline.afterMillis(30000),
                    )
                    assertEquals(NotificationProgress.Await, work.poll())
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    assertEquals(NotificationProgress.Await, work.poll())
                    work.close()
                    pool.close()
                    assertEquals(1, fixture.closed.count { it == 52900 })
                    assertTrue(fixture.replies.isEmpty())
                    assertEquals(0, fixture.writeCount)
                }
                }
            }
        }
    }

    @Test
    fun `queue saturation completes preparation with EAGAIN without waiting for a helper`() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = error("Unexpected socket request")

                override fun executeAuthority(job: TraceeAuthorityJob): SupervisorReplyEffect {
                    started.countDown()
                    assertTrue(stopped.await(5, TimeUnit.SECONDS))
                    return SupervisorReplyEffect.Error()
                }

                override fun close() {
                    stopped.countDown()
                }
            }
        }.use { pool ->
            fun job() =
                TraceeAuthorityJob(
                io.mazewall.platform.seccomp
                .SeccompNotification(42, 123, Arch.AMD64.audit, Arch.AMD64.openat, LongArray(6)),
                    Deadline.afterMillis(30000),
                    emptyList(),
            )
            pool.session().submitAuthority(job())
            assertTrue(started.await(5, TimeUnit.SECONDS))
            pool.session().submitAuthority(job())
            val overflow = job()
            val result = pool.session().submitAuthority(overflow).get(1, TimeUnit.SECONDS)
            assertEquals(SocketHelperResult.Failed(11), result)
            assertEquals(11, assertInstanceOf(SupervisorRoute.Abort::class.java, overflow.prepared.getNow(null).route).errno)
        }
    }
}
