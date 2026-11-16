---
title: "Align authorized accept routes with authority helper execution"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - "enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityExecution.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorNotificationMachine.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/TraceeAuthorityNativeContractTest.kt"
  - "docs/internals/designs/core/notification-liveness-contract.md"
target_symbols:
  - "execute"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Align authorized accept routes with authority helper execution

**Context:**
B6 inventory confirmed the JVM reducer selects Continue for allowed accept/accept4 but the authority executor permits Continue only for Spawn; existing tests cover the disconnected InjectFd branch.

**Needed:**
1. Add a compiling connected reducer-to-authority-executor regression for accepted and denied accept routes, preserving pointer-bearing open refusal and kernel peer-output semantics.
2. Cover accept/accept4 for Allow and InjectFd verdict forms, denial, spawn control and continued refusal for open/exec/connect/Unknown. Do not introduce remote peer-memory writes or open CONTINUE.
3. Reconcile current liveness guidance with the verified route and restore its moved historical issue reference. Preserve that historical resolution rather than editing it into a new result.

## Source evidence

- [SupervisorNotificationMachine.kt](../../../enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorNotificationMachine.kt):105–124 selects Continue for allowed Accept.
- [TraceeAuthorityDecisionMachine.kt](../../../enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityDecisionMachine.kt):9–18 carries that route through the session reducer; [TraceeAuthorityExecution.kt](../../../enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityExecution.kt):83 permits Continue only for Spawn. Its separate InjectFd branch87 accepts Accept, but the JVM reducer does not select it.
- Existing native contract91–106 expects accept Continue denial;114–120 tests only the disconnected InjectFd branch. The missing regression must compose the actual reducer/executor.
- [Historical accept resolution](resolved/issue-20260910-171157-430-restore-accept-peer-address-writes-through-identit.md):27–29 specifies kernel-mediated accept continuation for peer-output/EFAULT semantics. Current liveness prose122–125 retains older unsupported-output guidance.

Source-confirmed authorized-accept denial, not an executed runtime reproduction or a permission bypass. Independent inventory/intake lives under `.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/`; this separate issue does not expand B6's read-only-open implementation scope.

---
<!-- id: issue-20261105-070301-281-align-authorized-accept-routes-with-authority-help  file: issue-20261105-070301-281-align-authorized-accept-routes-with-authority-help.md -->
