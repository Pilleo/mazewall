package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.File

class DependencyGraphStandaloneTest {

    @Test
    fun `selectNextIssue returns null if no issues`() {
        assertNull(DependencyGraph.selectNextIssue(emptyList()))
    }
}
