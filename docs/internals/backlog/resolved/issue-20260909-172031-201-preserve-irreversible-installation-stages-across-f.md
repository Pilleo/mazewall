---
title: "Preserve irreversible installation stages across failure"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt"
target_symbols:
  - "ContainedExecutorWrapper"
open_questions: false
---

# Resolved: Preserve irreversible installation stages across failure

**Context:**
Wrapper rollback erases Landlock after later installation failure and Seccomp publication follows verification.

**Needed:**
1. Publish each successful native stage from its installer and expose partial progress without snapshot restoration.

**Resolution evidence (2026-09-09):** Removed installer/wrapper snapshot restoration.
Native stages publish before diagnostics, verification, and supervisor handshake;
coordinator publication targets the captured installing-thread holder. Attempt-local
`InstallationProgress` identifies irreversible stages without attributing another
installation's shared-state change to this attempt. Regressions cover worker Landlock
success followed by Seccomp failure, post-native verification failure, another
installation's progress, wrong-thread publication, and failed NEW_LISTENER propagation.
Focused tests passed after behavioral reds; full enforcer gate and design/diagram
evidence accompany architecture repair Task 3.

---
<!-- id: issue-20260909-172031-201-preserve-irreversible-installation-stages-across-f  file: issue-20260909-172031-201-preserve-irreversible-installation-stages-across-f.md -->
