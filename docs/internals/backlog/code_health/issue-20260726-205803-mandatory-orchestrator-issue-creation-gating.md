---
title: Enforce Mandatory Schema Gating and Skill Header Injection in Issue Creation
  API
severity: HIGH
status: open
priority: high
dependencies:
- issue-20260726-205802
component: orchestrator
target_modules:
- :tools:orchestrator
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/ReviewIssueLauncher.kt
effort: small
autonomy: autonomous
paperclip_issue_id: e2d5ace3-9641-4fc5-9ec9-189f5aaf5d92
---

# 🔴 [Severity: HIGH]: Enforce Mandatory Schema Gating and Skill Header Injection in Issue Creation API

**Context:**
Tasks generated manually or out-of-band often lack required metadata (such as numeric priority, non-empty `target_files`, or mandatory skill headers), leading to runtime errors or incorrect slot scheduling during Orchestrator daemon execution.

**Needed:**
1. Enforce strict frontmatter schema checking at issue creation time in `BacklogParser` and `ReviewIssueLauncher`.
2. Ensure all newly created issues automatically format `priority` as integer (0-10), include non-empty `target_files`, and inject the mandatory skill header.
3. Automatically format status as `open` so issue selection engines (`DependencyGraph`) pick up the issue without manual state intervention.
