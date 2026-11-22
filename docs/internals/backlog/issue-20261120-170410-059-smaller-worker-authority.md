---
title: "Smaller worker authority"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "portal-runtime"
target_modules: []
target_files: []
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Smaller worker authority

**Context:**
Allow workload-specific filesystem and network capabilities rather than one broad permission set; preserve legitimate operation through narrowly scoped grants. (Java4Shell-roadmap P2 #14)

**Needed:**
1. Define the workload-specific authority requirements for the intended Portal workload.
2. Replace the broad static permission set with narrowly scoped grants for that workload.
3. Add policy-scoping tests proving workers receive only their workload grants.
4. Preserve legitimate operation via integration tests on supported JDKs.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170410-059-smaller-worker-authority  file: issue-20261120-170410-059-smaller-worker-authority.md -->
