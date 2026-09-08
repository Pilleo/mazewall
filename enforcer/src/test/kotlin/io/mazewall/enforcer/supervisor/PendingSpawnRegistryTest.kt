package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PendingSpawnRegistryTest {
    private val tid = Tid(73_001)
    private val stackTrace = listOf(StackTraceElement("Example", "spawn", "Example.kt", 1))

    @AfterEach
    fun clearRegistry() {
        PendingSpawnRegistry.remove(tid)
    }

    @Test
    fun `returns entry within monotonic authorization ttl`() {
        PendingSpawnRegistry.register(tid, stackTrace, nowNanos = 100L)

        assertEquals(stackTrace, PendingSpawnRegistry.get(tid, nowNanos = 10_000_000_100L))
    }

    @Test
    fun `expires and removes entry after monotonic authorization ttl`() {
        PendingSpawnRegistry.register(tid, stackTrace, nowNanos = 100L)

        assertNull(PendingSpawnRegistry.get(tid, nowNanos = 10_000_000_101L))
        assertNull(PendingSpawnRegistry.get(tid, nowNanos = 10_000_000_101L))
    }
}
