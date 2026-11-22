---
title: "Portal process isolation"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "portal-runtime"
target_modules: []
target_files: []
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Portal process isolation

**Context:**
Confirm arbitrary native worker execution cannot read or modify the broker's memory; test ptrace, process_vm_readv/writev, /proc, and pidfd_getfd paths under the actual worker policy. (Java4Shell-roadmap P0 #3)

**Needed:**
1. Reproduce a native worker-side memory read/write attempt through each listed path under the current worker policy.
2. Close any path that reaches broker memory; document the residual cross-process surface.
3. Add disposable-host isolation regression tests for the closed paths.
4. Wire results into the Portal baseline qualification gates.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170313-197-portal-process-isolation  file: issue-20261120-170313-197-portal-process-isolation.md -->
