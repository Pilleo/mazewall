---
title: "Transition Orchestrator & Backlog Parser to Timestamp-Based Issue IDs"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: ["issue-20260726-01-rename-and-expand-create-backlog-issue-skill"]
component: "orchestrator"
target_modules: [":tools:orchestrator"]
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogParser.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Transition Orchestrator & Backlog Parser to Timestamp-Based Issue IDs

**Context:**
Sequential numeric issue IDs (such as `issue-190`, `issue-191`, `issue-210`) collide when created on different branches or by concurrent agents, and match historical closed GitHub issues during recovery lookups.

**Needed:**
1. Update `BacklogParser.kt` to support and validate timestamp-based issue ID formats (e.g. `issue-YYYYMMDD-HHMM-slug`).
2. Update `GitHubCli.kt` title prefix matching logic (`[$issueId]`) to parse timestamp-based issue IDs without integer collision risks.
3. Update dependency graph parsing to resolve timestamp-based dependency strings cleanly.
