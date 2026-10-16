package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class PortalWorkerOutputTest {
    @Test
    fun `diagnostics retain bounded sanitized lines and count discarded output`() {
        val output = (1..100).joinToString("\n") { "\u001b" + "x".repeat(5000) }
        val pump = PortalWorkerOutput(ByteArrayInputStream(output.toByteArray()))
        assertTrue(pump.ready.await(5, TimeUnit.SECONDS))
        pump.thread.join(5000)
        val snapshot = pump.snapshot()
        assertEquals(64, snapshot.lines.size)
        assertTrue(snapshot.lines.all { it.length <= 1024 && '\u001b' !in it })
        assertEquals(36L, snapshot.droppedLines)
        assertEquals(100L, snapshot.truncatedLines)
        assertFalse(pump.readinessObserved)
    }

    @Test
    fun `readiness requires a complete exact sentinel line`() {
        val pump = PortalWorkerOutput(ByteArrayInputStream("prefix MAZEWALL_PORTAL_WORKER_READY\n".toByteArray()))
        assertTrue(pump.ready.await(5, TimeUnit.SECONDS))
        assertFalse(pump.readinessObserved)
        val ready = PortalWorkerOutput(ByteArrayInputStream("MAZEWALL_PORTAL_WORKER_READY\n".toByteArray()))
        assertTrue(ready.ready.await(5, TimeUnit.SECONDS))
        assertTrue(ready.readinessObserved)
        ready.thread.join(5000)
    }

    @Test
    fun `output read failure is retained without false readiness`() {
        val failure = IOException("broken output pipe")
        val pump = PortalWorkerOutput(object : InputStream() {
            override fun read(): Int = throw failure
        })
        assertTrue(pump.ready.await(5, TimeUnit.SECONDS))
        assertSame(failure, pump.snapshot().failure)
        assertFalse(pump.readinessObserved)
    }
}
