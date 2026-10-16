package io.mazewall.profiler.internal

import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilerDaemonLifetimeTest {
    @Test
    @Timeout(10)
    fun `shutdown retains the daemon while sessions retire and rejects new sessions`() {
        val lifetime = ProfilerDaemonLifetime()
        val lease = lifetime.acquire()
        val drained = CompletableFuture<Boolean>()
        val closer = Thread { drained.complete(lifetime.awaitSessions(Duration.ofSeconds(5))) }
        closer.start()
        try {
            awaitCondition(description = "daemon rejects admission during shutdown") {
                try {
                    lifetime.acquire().close()
                    false
                } catch (_: IllegalStateException) {
                    true
                }
            }
            assertFalse(drained.isDone, "The acquired session still requires its daemon")
            lease.close()
            lease.close()
            assertTrue(drained.get(2, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { lifetime.acquire() }
        } finally {
            lease.close()
            closer.join(6_000)
            assertFalse(closer.isAlive)
        }
    }

    @Test
    fun `an unretired session exhausts a finite shutdown budget without reopening admission`() {
        val lifetime = ProfilerDaemonLifetime()
        val lease = lifetime.acquire()
        assertFalse(lifetime.awaitSessions(Duration.ZERO))
        assertFailsWith<IllegalStateException> { lifetime.acquire() }
        lease.close()
        assertTrue(lifetime.awaitSessions(Duration.ZERO))
    }

    @Test
    @Timeout(10)
    fun `interrupted shutdown stays bounded and preserves the interrupt signal`() {
        val lifetime = ProfilerDaemonLifetime()
        val lease = lifetime.acquire()
        val interrupted = CompletableFuture<Boolean>()
        val closer = Thread {
            val drained = lifetime.awaitSessions(Duration.ofSeconds(5))
            interrupted.complete(!drained && Thread.currentThread().isInterrupted)
        }
        closer.start()
        try {
            awaitCondition(description = "interruptible shutdown closes admission") {
                try {
                    lifetime.acquire().close()
                    false
                } catch (_: IllegalStateException) {
                    true
                }
            }
            closer.interrupt()
            assertTrue(interrupted.get(2, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { lifetime.acquire() }
        } finally {
            lease.close()
            closer.join(6_000)
            assertFalse(closer.isAlive)
        }
    }
}
