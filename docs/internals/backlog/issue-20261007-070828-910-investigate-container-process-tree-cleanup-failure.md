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

**Fresh serial reproduction and narrow runner repair:**
- After native task cache reuse was disabled, serial Docker full gate `run-8d042fba16e8` and isolated unchanged `CapturedLandlockBackendNativeTest` run `run-8152499da33d` reproduced forced-cleanup failure in both supervised capture cases.
- Temporary owned-descendant probes (`run-a13d65504543`) observed helpers reparent to PPid 1 and remain State Z (zombie). This is concrete evidence for the fresh serial case, not proof of every earlier concurrent failure.
- The container runner now requests the engine's init/reaper with `--init`. The same three native capture cases passed with zero skips (`run-ad04672603b2`). Temporary probes were removed; original assertions and timeouts remain unchanged.
- Fresh full mapped Docker gates passed in 2m38s (`run-a7557f9c33a8`). The runner repair reaps orphaned processes; it does not reinterpret zombies as successful cleanup or change production containment. Review remains pending.

---
<!-- id: issue-20261007-070828-910-investigate-container-process-tree-cleanup-failure  file: issue-20261007-070828-910-investigate-container-process-tree-cleanup-failure.md -->
