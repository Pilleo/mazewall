---
title: "Make socket helper owner-death procfs observation disappearance-safe"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperOwnerDeathTest.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make socket helper owner-death procfs observation disappearance-safe

**Context:**
During full enforcer verification the helper exited between Files.exists(/proc/pid) and reading stat, producing IOException: No such process at the exit predicate. Focused rerun passed; this is an observed test-probe race, not evidence of a helper lifecycle failure.

**Needed:**
1. Make the exit-state probe tolerate confirmed process disappearance while preserving unrelated I/O and permission failures; retain mandatory imported-listener release verification.

---
<!-- id: issue-20261006-174206-021-make-socket-helper-owner-death-procfs-observation-  file: issue-20261006-174206-021-make-socket-helper-owner-death-procfs-observation-.md -->
