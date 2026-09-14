package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SupervisorNotificationCancellationTest {
    @Test
    fun `closing notification cancels its helper and disposes late accepted fd without reply`() {
        val fixture = SupervisorBoundaryFixture()
        val admission = SupervisorAdmission()
        val executing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = SupervisorOwnedFd(TestFileDescriptors.replace<FileDescriptorRole.Generic>(52700), fixture.engine.fileSystem)
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    executing.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return SocketHelperResult.Accepted(accepted, byteArrayOf(), 0)
                }

                override fun close() {
                    release.countDown()
                }
            }
        }.use { pool ->
            fixture.handler(admission = admission, socketHelpers = pool).use { handler ->
                NativeArena.ofConfined().use { arena ->
                    with(arena) {
                        val work = handler.beginNotification(
                            fixture.notification(arena, Arch.AMD64.connect),
                            arena.allocate(Layouts.SECCOMP_NOTIF_RESP),
                            fixture.listener,
                            fixture.socket,
                            Deadline.afterMillis(30000),
                        )
                        repeat(3) { assertEquals(NotificationProgress.Await, work.poll()) }
                        assertTrue(executing.await(5, TimeUnit.SECONDS))
                        work.close()
                        work.close()
                        val leases = (0 until 8).map { checkNotNull(admission.acquire(Tid(1))) }
                        leases.forEach { it.close() }
                        pool.close()
                        assertTrue(fixture.replies.isEmpty())
                        assertEquals(1, fixture.closed.count { it == 52700 })
                        assertEquals(1, fixture.closed.count { it == 51006 })
                    }
                }
            }
        }
    }
}
