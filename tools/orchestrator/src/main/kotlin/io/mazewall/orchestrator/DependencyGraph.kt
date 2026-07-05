package io.mazewall.orchestrator

object DependencyGraph {
    fun selectNextIssue(issues: List<BacklogIssue>): BacklogIssue? {
        val openIssues = issues.filter { it.status == "open" }
        val openIds = openIssues.map { it.id }.toSet()

        // An issue is unblocked if none of its dependencies are currently open
        val unblockedIssues = openIssues.filter { issue ->
            issue.dependencies.none { dep -> openIds.contains(dep) }
        }

        // Sort by priority descending (scale of 0-10, highest first)
        // If priority is equal, sort by ID alphabetically/numerically (e.g. issue-001 before issue-002)
        return unblockedIssues.sortedWith(
            compareByDescending<BacklogIssue> { it.priority }
                .thenBy { it.id }
        ).firstOrNull()
    }
}
