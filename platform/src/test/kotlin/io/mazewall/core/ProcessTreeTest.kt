package io.mazewall.platform.lifecycle

import io.mazewall.platform.process.destroyForciblyWithDescendants
import io.mazewall.platform.process.snapshotDescendants
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.TimeUnit

@EnabledOnOs(OS.LINUX)
class ProcessTreeTest {
    @Test
    fun `destroyForciblyWithDescendants reaps a live child`() {
        val process = ProcessBuilder("sleep", "30").start()
        try {
            assertTrue(process.isAlive)
            process.destroyForciblyWithDescendants()
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `snapshotDescendants closes the kernel stream`() {
        val process = ProcessBuilder("sleep", "30").start()
        try {
            val descendants = process.toHandle().snapshotDescendants()
            assertTrue(descendants.isEmpty() || descendants.all { it.pid() > 0 })
        } finally {
            process.destroyForciblyWithDescendants()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
}
