---
title: "B5 D4 bind owned worker task lifecycle and guarded preinstall"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedInvocation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/RuntimeWorkerContext.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/FrozenStackScoping.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/OwnedStackScopingBootstrap.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedLiveScopingTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/ConfinedHelperRuntimeDrainTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/LiveScopingInstallationTest.kt"
target_symbols:
  - "OwnedDomain"
  - "OwnedInvocation"
  - "RuntimeWorkerContext"
  - "FrozenStackScoping"
  - "SupervisorInstaller"
  - "ContainedExecutors"
  - "OwnedStackScopingBootstrap"
open_questions: false
---

# 🟡 [Severity: HIGH]: B5 D4 bind owned worker task lifecycle and guarded preinstall

**Context:**
Parent issue 359: implement the accepted D4 exact worker/task ownership and absent-B6 pre-install refusal after bounded D3 acceptance.

**Needed:**
1. Deliver the complete accepted D4 worker/task lifecycle and pre-install refusal unit only after reviewed scope and controller release; preserve Legacy action-only callback/dispatch semantics.
2. Bind actual B4 helper Ready and exact captured native/engine/root/domain/generation/policy/resource identities before worker prestart. Register the real current worker before restrictions; absent B6 refuses before Landlock/seccomp/listener/body, without an injected readiness flag.
3. In Live only, bind Begin to the actual worker and body, preserve synchronous delivery context, merge canonical late observation and End result before physical bookkeeping, and never redeliver. Retain exact Pending/Unknown owners through join and same-manager retirement; no extra owner or implicit retry.
4. Prove actual worker/refusal and lifecycle race behavior with meaningful RED/GREEN and bounded real owners; label synthetic already-installed lifecycle fixtures honestly. Review ABI and root static gates and separately release a full build. No public/native activation or B4/B6 qualification follows.

Parent issue 359; issues 013, 509, 752, 856 and 869 remain open. [D4 preparation](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d4-lifecycle-preparation081/plan.md).

## Binding preparation detail

The [successor D4 plan](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d4-lifecycle-preparation081/plan-fixed1.md) records exact measured B4 support recipe and transactional rollback, checked sealed-owner PID/start and policy/resource/generation binding, Thread-identity worker retention, native-before-pins retirement, Live Begin/delivery/End barriers and cancellation/invalidation. Actual B4 Ready and absent-B6 refusal remain mandatory; any private shared-interpreter test seam and guard refinement require controller review before implementation. Synthetic capability tests cannot establish actual public Live readiness or native qualification.


Platform Register/Begin receipt attachment is a separate open prerequisite: [issue 892](issue-20261108-114247-892-retain-validated-live-worker-and-task-receipts-bef.md). Its four-file increment is accepted with explicit limits in the [084 controller decision](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-platform-receipt-full-build084/controller-decision.md). D4 must retain ambiguous `Refused` as Unknown, establish actual join and original-gate publication before manager recovery, and serialize carriers through its existing owner gate. This does not release D4 guard refinement or Live activation.

---
<!-- id: issue-20261108-113622-885-b5-d4-bind-owned-worker-task-lifecycle-and-guarded  file: issue-20261108-113622-885-b5-d4-bind-owned-worker-task-lifecycle-and-guarded.md -->
