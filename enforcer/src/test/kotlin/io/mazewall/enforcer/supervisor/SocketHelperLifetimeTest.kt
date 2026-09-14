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

class SocketHelperLifetimeTest {
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
