package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SocketHelperLifetimeTest {
    @Test
    fun `confined close timeout retains all owners until the actual I O loop quiesces`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val effects = mutableListOf<String>()
        val lifetime = SocketHelperLifetime({ effects += "reap" }, { effects += "channel" }, { effects += "endpoint" })
        val worker = Thread.ofPlatform().start {
            try {
                lifetime.runConfinedIo {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            } catch (problem: Throwable) {
                failure.set(problem)
            } finally {
                entered.countDown()
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertThrows(IllegalStateException::class.java) { lifetime.close() }
            assertFalse(lifetime.accepting)
            assertTrue(effects.isEmpty(), "No close/reap may race active native I/O")
            assertThrows(IllegalStateException::class.java) { lifetime.runConfinedIo { fail("Second I/O owner admitted") } }
        } finally {
            release.countDown()
            worker.join(2000)
            assertFalse(worker.isAlive)
            lifetime.close()
        }
        assertNull(failure.get())
        assertEquals(listOf("reap", "channel", "endpoint"), effects)
        lifetime.close()
        assertEquals(3, effects.size)
    }

    @Test
    fun `confined lifetime has one I O owner even before retirement and preserves action failure`() {
        val failure = IOException("read failed")
        val effects = mutableListOf<String>()
        val lifetime = SocketHelperLifetime({ effects += "reap" }, { effects += "channel" }, { effects += "endpoint" })
        assertSame(failure, assertThrows(IOException::class.java) { lifetime.runConfinedIo { throw failure } })
        assertThrows(IllegalStateException::class.java) { lifetime.runConfinedIo { fail("Retired I/O loop reused") } }
        lifetime.close()
        assertEquals(listOf("reap", "channel", "endpoint"), effects)
        assertThrows(IllegalStateException::class.java) { lifetime.runConfinedIo { fail("Closed I/O loop reused") } }
    }

    @Test
    fun `failed reap and endpoint cleanup retry without closing successful channel twice`() {
        var reaps = 0
        var channels = 0
        var endpoints = 0
        val delayedReap = IOException("child still alive")
        val transientEndpoint = IOException("endpoint temporarily busy")
        val lifetime = SocketHelperLifetime(
            reap = { if (++reaps == 1) throw delayedReap },
            closeChannel = { channels++ },
            closeEndpoint = { if (++endpoints == 1) throw transientEndpoint },
        )
        assertTrue(lifetime.accepting)
        assertSame(delayedReap, assertThrows(IOException::class.java) { lifetime.close() })
        assertFalse(lifetime.accepting, "failed close still stops admission")
        assertEquals(listOf(transientEndpoint), delayedReap.suppressed.toList())
        assertEquals(listOf(1, 1, 1), listOf(reaps, channels, endpoints))
        lifetime.close()
        lifetime.close()
        assertEquals(listOf(2, 1, 2), listOf(reaps, channels, endpoints))
    }

    @Test
    fun `successful reap stays complete while remaining channel cleanup retries`() {
        var reaps = 0
        var channels = 0
        var endpoints = 0
        val lifetime = SocketHelperLifetime(
            reap = { reaps++ },
            closeChannel = { if (++channels == 1) throw IOException("transient close failure") },
            closeEndpoint = { endpoints++ },
        )
        assertThrows(IOException::class.java) { lifetime.close() }
        lifetime.close()
        assertEquals(listOf(1, 2, 1), listOf(reaps, channels, endpoints))
    }
}
