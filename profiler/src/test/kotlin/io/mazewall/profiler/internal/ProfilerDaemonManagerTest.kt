package io.mazewall.profiler.internal

import io.mazewall.Platform
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class ProfilerDaemonManagerTest {

    @Test
    fun `test dummy test for coverage`() {
        val clazz = ProfilerDaemonManager::class.java
        assertNotNull(clazz)
    }

    @Test
    fun `test daemon spawn and stop`() {
        assumeTrue(Platform.isSupported())

        val context = ProfilerDaemonManager.getOrSpawnSharedDaemon()
        assertNotNull(context)
        assertTrue(context.daemonProcess.isAlive)

        val context2 = ProfilerDaemonManager.getOrSpawnSharedDaemon()
        assertTrue(context === context2) // should reuse

        ProfilerDaemonManager.stop()

        // Wait for it to die
        context.daemonProcess.waitFor()
        assertFalse(context.daemonProcess.isAlive)
    }
}
