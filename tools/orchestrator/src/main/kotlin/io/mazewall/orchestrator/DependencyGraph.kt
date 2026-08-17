package io.mazewall.orchestrator

object DependencyGraph {
    fun selectNextIssue(issues: List<BacklogIssue>): BacklogIssue? {
        val openIssues = issues.filter { it.status == "open" }
        val allActiveIds = issues.map { it.id }.toSet()

        // An issue is unblocked if none of its dependencies are currently in any active non-resolved status
        val unblockedIssues = openIssues.filter { issue ->
            issue.dependencies.none { dep -> allActiveIds.contains(dep) }
        }

        // Sort HIGH > MEDIUM > LOW, then ID descending
        return unblockedIssues.sortedWith(
            compareByDescending<BacklogIssue> { it.priority.rank }
                .thenByDescending { it.id }
        ).firstOrNull()
    }
}
