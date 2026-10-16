package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerCommandResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierEAllocationProbeTest {
    @Test
    fun `owned allocation probe removes only its exact per-run event`() {
        val commands = mutableListOf<List<String>>()
        val event = TierEAllocationProbe.nameFor("tier-e-probe-123")
        val owner = TierEAllocationProbe(event) { command ->
            commands += command
            ContainerCommandResult(0, if ("--list" in command) "probe_libc:malloc_foreign" else "ok")
        }
        owner.open()
        owner.close()
        assertTrue(event.startsWith("probe_libc:mazewall_malloc_"))
        assertEquals(listOf("perf", "probe", "--del", event), commands.last())
        assertTrue(commands.none { it.takeLast(1) == listOf("probe_libc:malloc") })
    }

    @Test
    fun `preexisting exact event cannot be adopted or deleted`() {
        val event = TierEAllocationProbe.nameFor("tier-e-probe-456")
        val commands = mutableListOf<List<String>>()
        val owner = TierEAllocationProbe(event) { command ->
            commands += command
            ContainerCommandResult(0, if ("--list" in command) "$event (on malloc)" else "ok")
        }
        assertThrows(IllegalArgumentException::class.java) { owner.open() }
        owner.close()
        assertTrue(commands.none { "--del" in it })
    }
}
