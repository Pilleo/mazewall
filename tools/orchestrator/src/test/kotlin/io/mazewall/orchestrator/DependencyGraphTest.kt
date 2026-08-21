package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.File

class DependencyGraphTest {

    @Test
    fun `selectNextIssue returns null if no issues`() {
        assertNull(DependencyGraph.selectNextIssue(emptyList()))
    }

    @Test
    fun `dependent tasks are blocked while parent dependency is active in any non-resolved status`() {
        // Parent dependency is in 'in_progress' status, which is non-resolved.
        val parentIssue = BacklogIssue(
            file = File("issue-001.md"),
            id = "issue-001",
            title = "Parent Task",
            priority = BacklogPriority.MEDIUM,
            status = "in_progress",
            dependencies = emptyList()
        )

        // Dependent task is 'open' but has dependency on issue-001.
        val dependentIssue = BacklogIssue(
            file = File("issue-002.md"),
            id = "issue-002",
            title = "Dependent Task",
            priority = BacklogPriority.HIGH,
            status = "open",
            dependencies = listOf("issue-001")
        )

        // Dependent task should be blocked because the parent is active / unresolved (in allActiveIds)
        val selected = DependencyGraph.selectNextIssue(listOf(parentIssue, dependentIssue))
        assertNull(selected, "Dependent task should be blocked and not selected")
    }

    @Test
    fun `dependent tasks are unblocked and selected once parent dependency is resolved`() {
        // Parent dependency is resolved, which means it won't be passed in 'issues' (or won't be active)
        // BacklogParser.parseAllIssues does not include resolved issues because it filters out the 'resolved' folder.
        // Therefore, resolved issues are absent from the active issues list passed to DependencyGraph.
        val dependentIssue = BacklogIssue(
            file = File("issue-002.md"),
            id = "issue-002",
            title = "Dependent Task",
            priority = BacklogPriority.HIGH,
            status = "open",
            dependencies = listOf("issue-001")
        )

        val selected = DependencyGraph.selectNextIssue(listOf(dependentIssue))
        assertEquals(dependentIssue, selected, "Dependent task should be unblocked and selected when parent is resolved/absent")
    }
}
