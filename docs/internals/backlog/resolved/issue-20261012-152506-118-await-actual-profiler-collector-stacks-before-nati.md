---
title: "Await actual profiler collector stacks before native custody assertion"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/PortalWorkerPolicyNativeTest.kt"
target_symbols:
  - "observeCollectors"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Await actual profiler collector stacks before native custody assertion

**Context:**
**Resolution — 2026-10-12:** Needed step1 fulfilled: bounded actual listener-owned stack observation retains exact two-owner predicate and native budget, with all profile/install/post-JIT/end assertions. E=`/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/`: `task-6d2/fix-review.md` and fresh native PASS; `task-6d2/continuation-20261012-a1/task-6d2-checkpoint-review.md` accepts fresh owning case and complete producer617 gate. Earlier exit1 retained, no owner-count relaxation. [Acceptance](../../reviews/2026-10-12-integration-contract-completion.md).

Task6d2 actual complete gate exited 1: one-shot collector snapshot observed one of two asynchronously started session threads; exact two-owner and physical-end assertions must remain mandatory.

**Needed:**
1. Bound fixture observation by actual listener-owned stack readiness, retain failure on missing collectors, verify native profile/install/JIT and request controller review before source acceptance.

---
<!-- id: issue-20261012-152506-118-await-actual-profiler-collector-stacks-before-nati  file: issue-20261012-152506-118-await-actual-profiler-collector-stacks-before-nati.md -->
