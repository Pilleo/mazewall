package io.mazewall.orchestrator

/**
 * Pure selection core for dispatch: which board issue runs next, if any.
 * Mirrors the semantics of the previous bash implementation exactly:
 * - only issues ingested from the markdown backlog (description marker);
 * - status `backlog`, unassigned;
 * - every blocker terminal (`done`/`cancelled`);
 * - highest priority wins, lowest issueNumber breaks ties.
 */
object DispatchSelector {

    private val PRIORITY_RANK = mapOf("high" to 2, "medium" to 1, "low" to 0)

    fun isDispatchable(issue: PaperclipIssue): Boolean =
        issue.status == "backlog" &&
            issue.fromMarkdownBacklog &&
            issue.assigneeAgentId == null &&
            issue.blockedBy.all { it.status == "done" || it.status == "cancelled" }

    /** All dispatchable issues in deterministic execution order (priority desc, number asc). */
    fun ordered(issues: List<PaperclipIssue>): List<PaperclipIssue> =
        issues.filter(::isDispatchable).sortedWith(comparator)

    fun select(issues: List<PaperclipIssue>): PaperclipIssue? = ordered(issues).firstOrNull()

    private val comparator =
        compareByDescending<PaperclipIssue> { PRIORITY_RANK[it.priority] ?: 0 }
            .thenBy { it.issueNumber ?: Int.MAX_VALUE }
}
