package io.mazewall.profiler.triage

import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ManagedProcessCaptureTest {
    @Test
    fun `capture closes and reaps a completed child`() {
        ManagedProcessCapture.start(ProcessBuilder("sh", "-c", "printf ready")).use { capture ->
            assertEquals("ready", capture.readStdout(Duration.ofSeconds(2)))
            assertEquals(0, capture.awaitExit(Duration.ofSeconds(2)))
        }
    }

    @Test
    fun `output timeout force stops and reaps the child`() {
        lateinit var capture: ManagedProcessCapture

        assertFailsWith<IOException> {
            ManagedProcessCapture.start(ProcessBuilder("sh", "-c", "sleep 30")).use {
                capture = it
                it.readStdout(Duration.ofMillis(50))
            }
        }

        assertFalse(capture.process.isAlive)
    }
}
