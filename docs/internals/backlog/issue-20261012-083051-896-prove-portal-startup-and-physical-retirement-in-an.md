---
title: "Prove Portal startup and physical retirement in an owned delegated cgroup"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerLaunchProfileTest.kt"
target_symbols:
  - "RequiredWorkerLaunchProfileTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prove Portal startup and physical retirement in an owned delegated cgroup

**Context:**
Task7a needs actual finite cpu and memory controls, attachment before permit, independent readiness and physical cleanup; ordinary-directory refusal alone is not positive kernel proof.

**Needed:**
1. Add and execute a finite fresh delegated user-unit native fixture with custody receipts.

**Task7a implementation:**
The positive fixture now checks actual cpu/memory limits and PID membership before
the broker can send parent authorization, completes genuine independent worker
ACK/readiness and guest dispatch, and checks physical worker/resource retirement.
`portals/runtime/DELEGATED_CGROUP_TEST.md` documents fresh-unit custody and the
named default prerequisite versus fail-closed explicitly unsupported roots.
Local native evidence does not grant production approval. Keep this issue open
through the separate combined integration-contract acceptance checkpoint.

---
<!-- id: issue-20261012-083051-896-prove-portal-startup-and-physical-retirement-in-an  file: issue-20261012-083051-896-prove-portal-startup-and-physical-retirement-in-an.md -->
