---
title: "Make stateful Portal admission atomic with terminal state loss"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [portals-runtime]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalStatefulInvocationGate.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalStatefulAdmission.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalRuntimeTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalBrokerOwnershipTest.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Make stateful Portal admission atomic with terminal state loss

**Context:**
Approved P1 requires one active stateful invocation and terminal unavailable state after ambiguous completion. Current check-then-call permits overlapping invocations and a race past state loss. Serialize per-group decision/admission, classify overlapping rejection as NOT_STARTED, and recheck loss atomically.

**Needed:**
1. Use latches to prove overlapping stateful calls never enter and post-loss calls cannot pass admission.

## Evidence

Per-group synchronized admission and terminal loss now share a pure phase/event evaluator. One stateful body can be active; an overlapping caller rejects with NOT_STARTED before entry. UNKNOWN_COMPLETION or fatal invocation error terminally loses the group. Delayed completion cannot reopen state. Stateless calls retain independent execution semantics.

The overlap/loss regression failed before implementation (`run-3d2878bf5c9d`) and passed afterward (`run-02defd954f2a`). Fatal identity/terminal loss and full Portal host/quality cases passed. Broker process integration passed in 26s (`run-e176641dc462`); one shared ownership/configuration/admission merge checkpoint passed in 1m43s, 265 tasks (42 executed, 223 up-to-date), `run-21e594d7cc0f`. Warning budget was preserved by extracting admission/dispatch decisions rather than suppressing complexity. Issue stays open for repository review.

---
<!-- id: issue-20261007-174835-824-make-stateful-portal-admission-atomic-with-termina  file: issue-20261007-174835-824-make-stateful-portal-admission-atomic-with-termina.md -->
