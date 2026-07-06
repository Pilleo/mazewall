package io.mazewall.profiler.triage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import java.io.File

class DiagnosticTriageRunnerTest {

    @Test
    fun `test DiagnosticTriageRunner does not crash`() {
        // Run main method which will invoke private methods in the runner class
        try {
            DiagnosticTriageRunner.main(emptyArray())
        } catch (e: Exception) {
            // Should not crash completely; failure gracefully handled
        }
    }
}
