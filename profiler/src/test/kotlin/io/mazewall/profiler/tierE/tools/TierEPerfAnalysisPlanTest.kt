package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEPerfAnalysisPlanTest {
    @Test
    fun `perf analysis retains process identity and monotonic timestamp streams`() {
        val commands = TierEPerfAnalysisPlan.commands("perf")
        assertEquals(listOf("perf", "report", "--stdio", "--no-children"), commands[1].take(4))
        assertEquals("comm,pid,tid,time,event,ip,sym,dso", commands.last()[commands.last().indexOf("-F") + 1])
        assertEquals(
            listOf("perf", "script", "-f", "-i", "/artifacts/allocation.data"),
            TierEPerfAnalysisPlan.commands("allocation").single(),
        )
        assertThrows(IllegalArgumentException::class.java) { TierEPerfAnalysisPlan.commands("unknown") }
    }
}
