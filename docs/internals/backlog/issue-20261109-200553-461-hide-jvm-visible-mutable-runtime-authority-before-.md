---
title: "Hide JVM-visible mutable runtime authority before reporting retirement"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntimeAcquisitionOwner.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
target_symbols:
  - "ManagedRuntime"
  - "ManagedRuntimeAcquisitionOwner"
open_questions: false
---

# 🟡 [Severity: HIGH]: Hide JVM-visible mutable runtime authority before reporting retirement

**Context:**
The current unqualified core candidate has a reproduced false-retirement outcome in [authority-red01.log](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-direct-owner-implementation/component12-runtime-owner-implementation/authority-red01.log), SHA-256 `5027780504d8fe4e751853c63cbf3652e720c7e4310d112cac2fc937bf12b57f`: with a real canonical runtime and live worker, JVM-visible `ManagedRuntime.access$getDomains$p` returns the actual map; clearing it let owner retirement report `Complete` while the worker remained alive (`Pending` was expected). This reproduces the failure on the current unqualified candidate. It is not evidence of a historical native exploit or validation of every route.

The [JVM authority audit](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-direct-owner-implementation/component12-runtime-owner-implementation/jvm-existing-authority-blocker.md), SHA-256 `7dd6b4482dad888b77e7d5fc846f1bde9c5f7ec988d4d73203630c1f85043da7`, and [refactor review](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-direct-owner-implementation/component12-runtime-owner-implementation/refactor-only-independent-review.md), SHA-256 `98900d7627636c5d1c5d9b013216a674b53bdc527643fba895f197b10eb5be26`, document the broader JVM-visible raw manager/environment/map/preparation authority surface. The live-worker repro specifically exercised the mutable domain-map accessor; helper-failure/Future/preparation authority risks are source-backed review concerns, not individually reproduced here.

This is separate from [issue 338](issue-20261106-013649-338-retain-failed-prepared-acquisition-rollback-owners.md)'s prepublication rollback-owner gap and [issue 507](resolved/issue-20261109-192741-507-reject-process-wide-act-notify-before-supervisor-a.md)'s process-wide ACT_NOTIFY pre-effect supervisor acquisition. Track related boundaries without merging causes. Correct the JVM-visible mutable authority and retain `Pending` until actual physical worker exit; add the reflection regression without `setAccessible`. The current candidate is unqualified; no other route or production behavior is accepted by this issue.

**Needed:**
1. Remove or make non-authoritative the JVM-visible mutable runtime authority used to falsify retirement, and add an actual reflection regression that preserves the live worker and verifies Pending until physical exit; audit related generated accessors without claiming untested routes were reproduced.

---
<!-- id: issue-20261109-200553-461-hide-jvm-visible-mutable-runtime-authority-before-  file: issue-20261109-200553-461-hide-jvm-visible-mutable-runtime-authority-before-.md -->
