package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SocketHelperPrewarmTest {
    @Test
    fun `confined reservation spends exactly one prewarmed slot and failed reap remains retryable`() {
        val created = AtomicInteger()
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seen = AtomicReference<ConfinedHelperJob>()
        val reapFailure = IllegalStateException("controlled physical reap failure")
        val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
            created.incrementAndGet()
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = error("Legacy operation in confined pool")

                override fun runConfined(exchange: ConfinedHelperJob) {
                    seen.set(exchange)
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }

                override fun close() {
                    if (closes.incrementAndGet() == 1) throw reapFailure
                }
            }
        }
        try {
            val manifest = ConfinedHelperTestData.manifest()
            val deadline = ConfinedDeadline(100)
            assertThrows(IllegalStateException::class.java) { pool.reserveConfined(manifest, deadline) }
            assertThrows(IllegalStateException::class.java) { pool.session() }
            pool.prewarm()
            assertEquals(1, created.get())
            val session = pool.reserveConfined(manifest, deadline)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(ConfinedDelivery.Relay, seen.get().delivery)
            assertThrows(IllegalStateException::class.java) { pool.reserveConfined(manifest, deadline) }
            session.cancel()
            assertTrue(seen.get().cancelled)
            release.countDown()
            assertThrows(IllegalStateException::class.java) { session.close() }
            assertFalse(session.confinedRetired)
            assertEquals(1, closes.get())
            session.close()
            assertTrue(session.confinedRetired)
            assertEquals(2, closes.get())
            assertThrows(IllegalStateException::class.java) { pool.reserveConfined(manifest, deadline) }
            assertEquals(1, created.get())
        } finally {
            release.countDown()
            pool.close()
        }
    }

    @Test
    fun `prewarm owns every child before returning and shutdown closes each once`() {
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        SocketHelperPool(3, 1) {
            starts.incrementAndGet()
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    descriptor: SupervisorOwnedFd,
                ): SocketHelperResult = error("prewarm must not perform an operation")

                override fun close() {
                    closes.incrementAndGet()
                }
            }
        }.use { pool ->
            pool.prewarm()
            assertEquals(3, starts.get())
            assertEquals(0, closes.get())
        }
        assertEquals(3, closes.get())
    }
}
