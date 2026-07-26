package io.mazewall.profiler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProfilerClassCoverageTest {

    @Test
    fun `test Profiler class instantiation`() {
        assertNotNull(Profiler)
    }

    @Test
    fun `test BillOfBehavior class properties`() {
        val bob = BillOfBehavior(
            opens = setOf("/tmp"),
            fsWritePaths = setOf("/tmp/test"),
            syscalls = setOf(io.mazewall.core.Syscall.OPEN),
            stackProfile = emptyMap()
        )
        assertEquals(1, bob.opens.size)
        assertEquals(1, bob.fsWritePaths.size)
        assertEquals(1, bob.syscalls.size)
        assertTrue(bob.stackProfile.isEmpty())
    }

    @Test
    fun `test ProfilingResult properties`() {
        val bob = BillOfBehavior(emptySet(), emptySet(), emptySet())
        val res = ProfilingResult("test", bob, emptyMap())
        assertEquals("test", res.value)
        assertEquals(bob, res.behavior)
        assertTrue(res.stackProfile.isEmpty())
        assertNotNull(res.toString())
    }
}
