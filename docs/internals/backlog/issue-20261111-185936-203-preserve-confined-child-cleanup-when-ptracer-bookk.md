---
title: "Preserve confined child cleanup when ptracer bookkeeping throws"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonManager.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManagerTest.kt"
target_symbols:
  - "cleanupDaemon"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve confined child cleanup when ptracer bookkeeping throws

**Context:**
In the confined cleanup path, DaemonPtracer.retire can propagate a Throwable from engine.process before the already-owned child resources are released, skipping child, hook, pump, and endpoint cleanup. Add the owning regression only in enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManagerTest.kt. This is separate from issue 842 captured-engine authority and must not change legacy behavior or GlobalChildProcessResources. See the [next-increment decision brief](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/original-owner-next-increment-decision-20261111-001.md), SHA-256 `86cb2aa4be5e58de21b5f17c360ec52525aa0a67bbc739084bf1a4f2ebee6943`.

**Needed:**
1. Add a confined successful-start then stop RED: make MockNativeEngine.process throw the exact saved fatal LinkageError during ptracer retirement; assert identical fatal propagation and independent first cleanup attempts for the original child, hook, and endpoint. Then design the narrow confined cleanup/failure fold to retain raw first-failure identity and interruption, without replaying ambiguous destructive actions; add second-cleanup-failure and fatal-precedence controls. Keep changes to SupervisorDaemonManager.kt and SupervisorDaemonManagerTest.kt; preserve legacy and GlobalChildProcessResources. If no-replay cannot be guaranteed with the existing resource owner, stop for a separate design.

---
<!-- id: issue-20261111-185936-203-preserve-confined-child-cleanup-when-ptracer-bookk  file: issue-20261111-185936-203-preserve-confined-child-cleanup-when-ptracer-bookk.md -->
