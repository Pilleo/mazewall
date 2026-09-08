package io.mazewall.enforcer.supervisor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ProcFsInspectorTest {
    @Test
    fun `parent pid parsing distinguishes malformed proc data from a real zero`() {
        assertEquals(ProcFsInspector.PidObservation.Observed(42), ProcFsInspector.parsePpid("99 (worker) S 42 1 1"))
        assertEquals(ProcFsInspector.PidObservation.Malformed, ProcFsInspector.parsePpid("not a proc stat record"))
    }
}
