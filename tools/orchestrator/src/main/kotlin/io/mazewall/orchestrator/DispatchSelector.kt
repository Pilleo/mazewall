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

    /**
     * Forced targeting (orchestrator FORCE_TASK parity): restricts the ordered
     * candidates to [identifier] when provided. All safety gates (marker,
     * terminal blockers, backlog status) still apply - forcing changes WHICH
     * dispatchable issue runs, never WHETHER one may run.
     */
    fun select(issues: List<PaperclipIssue>, forceIdentifier: String?): PaperclipIssue? {
        val scoped = if (forceIdentifier.isNullOrBlank()) issues
        else issues.filter { it.identifier.equals(forceIdentifier, ignoreCase = true) }
        return ordered(scoped).firstOrNull()
    }

    fun select(issues: List<PaperclipIssue>): PaperclipIssue? = select(issues, null)

    private val comparator =
        compareByDescending<PaperclipIssue> { PRIORITY_RANK[it.priority] ?: 0 }
            .thenBy { it.issueNumber ?: Int.MAX_VALUE }
}
