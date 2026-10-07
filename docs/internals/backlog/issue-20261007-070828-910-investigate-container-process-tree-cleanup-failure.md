---
title: "Investigate container process-tree cleanup failure under concurrent verification"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate container process-tree cleanup failure under concurrent verification

**Context:**
Container run run-98232dc52248 failed three IsolatedProcessTesterLifecycleTest cases while host and container Gradle gates shared the checkout concurrently. Cleanup could not confirm termination after forced cleanup. Serial unchanged container run run-54c95a6f71f6 passed. Root cause is unproven; do not classify this as a verifier defect or claim a lifecycle fix.

**Needed:**
1. Reproduce in an isolated output tree and retain descendant PID and process-state evidence; keep host and container builds serial in the shared checkout.

---
<!-- id: issue-20261007-070828-910-investigate-container-process-tree-cleanup-failure  file: issue-20261007-070828-910-investigate-container-process-tree-cleanup-failure.md -->
