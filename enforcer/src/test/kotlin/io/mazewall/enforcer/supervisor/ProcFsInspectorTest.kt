package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ProcFsInspectorTest {
    @Test
    fun `parent pid parsing distinguishes malformed proc data from a real zero`() {
        assertEquals(ProcFsInspector.PidObservation.Observed(42), ProcFsInspector.parsePpid("99 (worker) S 42 1 1"))
        assertEquals(ProcFsInspector.PidObservation.Malformed, ProcFsInspector.parsePpid("not a proc stat record"))
    }
}
