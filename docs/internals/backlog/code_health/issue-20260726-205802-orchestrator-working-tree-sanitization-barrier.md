---
title: Implement Pre-Commit Working Tree Sanitization Barrier in Orchestrator Tasks
severity: HIGH
status: open
priority: 10
dependencies:
- issue-20260726-205801
component: orchestrator
target_modules:
- :tools:orchestrator
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt
effort: medium
autonomy: autonomous
---

# 🔴 [Severity: HIGH]: Implement Pre-Commit Working Tree Sanitization Barrier in Orchestrator Tasks

**Context:**
AI agent sessions often accidentally stage un-scoped file edits (such as modified resolved backlog items or scratch artifacts) when preparing PR commits. This results in 50+ file noise diffs in PRs that make code review difficult and increase merge conflict surface area.

**Needed:**
1. Add an automated sanitization barrier in Orchestrator prior to PR creation or task completion.
2. Automatically execute `git checkout origin/master -- docs/internals/backlog/resolved/` before committing or force-pushing to ensure resolved backlog files are never modified in PR diffs.
3. Inspect staged diff file count: reject or clean commits that touch more than declared `target_files` plus task-specific additions.
