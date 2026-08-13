package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubCliDiagnosticsTest {
    @Test
    fun `proxy diagnostics report configuration without exposing credentials`() {
        assertEquals("configured", proxyConfigurationStatus("https://user:secret@proxy.example:8443"))
    }

    @Test
    fun `proxy diagnostics distinguish missing and blank values`() {
        assertEquals("not configured", proxyConfigurationStatus(null))
        assertEquals("not configured", proxyConfigurationStatus("  "))
    }
}
