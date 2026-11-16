---
title: "Preserve supervisor startup failure when helper shutdown also fails"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonEngine.kt"
target_symbols:
  - "SupervisorDaemonEngine"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve supervisor startup failure when helper shutdown also fails

**Context:**
The run lifecycle closes the helper pool in a plain finally; a close failure can replace an earlier startup or delegate failure. This is a source-level reporting risk, not an established cause of issue 051; preserve fail-closed behavior and ownership uncertainty.

**Needed:**
1. Define a narrow lifecycle failure-precedence boundary using existing CleanupFailures semantics and focused tests; retain Error promotion, interruption, exact cleanup attempts, existing deadlines, and uncertain retirement.

---
<!-- id: issue-20261112-051312-407-preserve-supervisor-startup-failure-when-helper-sh  file: issue-20261112-051312-407-preserve-supervisor-startup-failure-when-helper-sh.md -->
