package io.mazewall.orchestrator

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubCliDiagnosticsTest {
    @Test
    fun `proxy diagnostics report configuration without exposing credentials`() {
        assertEquals("configured", proxyConfigurationStatus("https://user:secret@proxy.example:8443"))
    }

    @Test
    fun `proxy diagnostics distinguish missing and blank values`() {
        assertEquals("not configured", proxyConfigurationStatus(null))
        assertEquals("not configured", proxyConfigurationStatus("  "))
    }

    @Test
    fun `diagnostic helper destroys a hung process instead of blocking on stdout`() {
        val process = ProcessBuilder("sleep", "30").start()
        try {
            val started = System.nanoTime()
            val output = readProcessOutputOrDestroy(process, 200, TimeUnit.MILLISECONDS)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertNull(output)
            assertTrue(elapsedMs < 5_000, "timed wait should return quickly, took ${elapsedMs}ms")
            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "hung diagnostic process should be destroyed")
        } finally {
            process.destroyForcibly()
        }
    }
}
