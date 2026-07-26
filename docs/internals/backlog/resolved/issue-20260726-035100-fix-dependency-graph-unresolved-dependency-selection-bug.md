---
title: "Fix DependencyGraph Selecting Tasks with Unresolved Active Dependencies"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/DependencyGraph.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/DependencyGraphTest.kt"
effort: "small"
autonomy: "autonomous"
github_issue: 322
---

# 🔴 [Severity: HIGH]: Fix DependencyGraph Selecting Tasks with Unresolved Active Dependencies

**Context:**
`DependencyGraph.selectNextIssue` currently filters `openIssues = issues.filter { it.status == "open" }` and extracts `openIds`. It then checks if an issue's dependencies are in `openIds`.
If a dependency issue is in `in_progress`, `pending`, or any active non-resolved status, its ID is absent from `openIds`. Consequently, `issue.dependencies.none { dep -> openIds.contains(dep) }` evaluates to `true`, causing `DependencyGraph` to falsely treat dependent tasks as unblocked and prompt the operator for approval before their prerequisites are resolved.

**Needed:**
1. Update `DependencyGraph.selectNextIssue` to check dependencies against ALL active (unresolved) issue IDs present in the backlog (`allActiveIds = issues.map { it.id }.toSet()`).
2. An issue with status `"open"` must ONLY be considered unblocked if none of its declared dependencies exist in `allActiveIds`.
3. Add a unit test in `DependencyGraphTest.kt` verifying that dependent tasks are blocked while their parent dependency issue is active in any non-resolved status (`in_progress`, `open`, etc.).
