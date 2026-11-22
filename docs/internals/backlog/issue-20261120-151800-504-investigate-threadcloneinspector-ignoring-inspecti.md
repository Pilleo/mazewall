---
title: "Investigate ThreadCloneInspector ignoring InspectionContext.allowNonThreadClone on default-ALLOW policies"
severity: "LOW"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/SyscallInspector.kt"
target_symbols: 
  - "ThreadCloneInspector"
open_questions: false
---

# 🟡 [Severity: LOW]: Investigate ThreadCloneInspector ignoring InspectionContext.allowNonThreadClone on default-ALLOW policies

**Context:**
SyscallInspector InspectionContext.allowNonThreadClone is never read; on default-ALLOW policies ThreadCloneInspector emits dead BPF argument-check instructions where both branches return ACT_ALLOW. Deferred to 0.2 fork/vfork/clone policy evaluation to keep 0.1.0 D2 syscall set untouched.

**Needed:**
1. Evaluate skipping ThreadCloneInspector when resolved clone action equals ACT_ALLOW alongside 0.2 fork/vfork/clone policy design.

---
<!-- id: issue-20261120-151800-504-investigate-threadcloneinspector-ignoring-inspecti  file: issue-20261120-151800-504-investigate-threadcloneinspector-ignoring-inspecti.md -->
