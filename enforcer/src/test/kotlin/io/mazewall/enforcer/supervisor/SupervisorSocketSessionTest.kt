package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SupervisorSocketSessionTest {
    @Test
    fun `daemon shutdown owns and closes its shared helper capacity`() {
        val fixture = SupervisorBoundaryFixture()
        val pool = SocketHelperPool(1, 1) { error("idle daemon must not launch a helper") }
        val daemon = SupervisorDaemonEngine("/tmp/unused-socket-helper-owner", fixture.engine, fixture.sockets, pool)
        daemon.triggerGlobalShutdown("test")
        assertThrows(IllegalStateException::class.java) { pool.session() }
    }

    @Test
    fun `closing session cancels pending socket operation without a late reply`() {
        val fixture = SupervisorBoundaryFixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return SocketHelperResult.Connected
                }

                override fun close() {
                    release.countDown()
                }
            }
        }.use { pool ->
            val handler = SupervisorSessionHandler(fixture.socket, fixture.listener, fixture.engine, fixture.sockets, socketHelpers = pool)
            NativeArena.ofConfined().use { arena ->
                // Direct approved connect route avoids tracee-memory inspection in this lifecycle test.
                assertTrue(with(arena) { handler.readAndHandleJvmResponse(fixture.context(arena, Arch.AMD64.connect)) })
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            handler.close()
            pool.close() // Cancellation schedules reap; explicit shutdown joins owned cleanup.
            assertEquals(0, fixture.replies.size)
            assertEquals(setOf(51005, 51006), fixture.closed.toSet())
        }
    }
}
