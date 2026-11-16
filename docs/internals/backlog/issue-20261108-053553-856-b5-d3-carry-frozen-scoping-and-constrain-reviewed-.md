---
title: "B5 D3 carry frozen scoping and constrain reviewed routes"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/FrozenStackScoping.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/RuntimeWorkerContext.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/OwnedStackScopingBootstrap.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/ConfinedHelperRuntimeDrainTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedStackScopingBootstrapTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/SupervisorBpfCompilationTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/FrozenStackScopingTest.kt"
target_symbols:
  - "FrozenStackScoping"
  - "ManagedRuntime"
  - "OwnedDomain"
  - "RuntimeWorkerContext"
  - "ContainedExecutors"
  - "OwnedStackScopingBootstrap"
open_questions: false
---

# 🟡 [Severity: HIGH]: B5 D3 carry frozen scoping and constrain reviewed routes

**Context:**
Parent issue 359: preserve the exact frozen scoping variant across the installer while retaining D2 Live refusal and enforcing reviewed route limits before Reserve.

**Needed:**
1. Carry the exact frozen Legacy or Live object through the actual manager, domain, worker and installer; preserve the same frozen Legacy policy/session identity and existing public descriptors. Keep both early Live guards and default Legacy behavior.
2. Permit each effective selected Live route only when its existing action is ACT_ALLOW, before Reserve and again after claim before helper/install. Keep exact reviewed route subset, deny unused supported route and openat2, reject unrelated Live notifications, and preserve original argument rules and JVM floor. No broader Legacy fix.
3. Verify actual pre-Reserve refusal, guarded Live and reachable Legacy behavior, BPF simulation and preserved ownership using behavioral RED/GREEN with nonzero owning XML. Run required static/API checks and independently reviewed full build before scoped acceptance. No native/JNI/public activation, B4/B6 release or qualification follows.

Parent issue 359; issue 013 remains open including clean-CLOSED-first proof. [D3 preparation](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d3-frozen-carry-preparation075/plan-fixed1.md); implementation requires controller release.

## Binding preparation clarification

Pre-Reserve refusal asserts zero Reserve/owner/helper/Landlock/seccomp/listener/body effects and correct admission/original-owner cleanup. Post-claim rechecks account for already acquired originals and assert no additional helper, prestart, install/restriction, listener or body effects; naturally unproducible immutable-policy mismatches are pure negatives, not fabricated public traces.

Keep both Live guards. Verify reachable Legacy manager-to-installer identity, actual public Live-to-domain refusal, and separate internal Live planner/installer refusal. The actual dynamic Live worker-to-installer edge remains **OPEN** pending D4 and its prerequisites; no synthetic worker or guard bypass qualifies it. The Live first envelope denies unselected supported OPEN/OPENAT and OPENAT2 even if base allowed, rejects unrelated notifications/handler keys, and retains original argument rules and JVM floor. [Issue 509](issue-20261105-055732-509-preserve-static-syscall-denials-when-adding-scopin.md) stays open for broader Legacy behavior.

[Controller clarification](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d3-frozen-carry-preparation075/controller-plan-clarifications.md) and [successor plan](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d3-frozen-carry-preparation075/plan-fixed2.md) bind implementation review; no new native/provider authority or activation is authorized.

## Bounded implementation status

The controller accepted the frozen-value carry and reviewed route-planning increment with explicit limits after the focused checks, genuine Legacy session identity proof and full build. See the [controller decision](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d3-frozen-carry-full-build080/controller-decision.md) and its retained evidence. The actual dynamic Live worker-to-installer proof remains **OPEN**; both Live guards and the default Unsupported provider remain in place, with no Live or native activation authorized.

The real Legacy test observed exact policy/session identity and process exits, but daemon descriptor cleanup is not established: observed EBADF failures remain under [issue 869](issue-20261108-113441-869-investigate-supervisor-daemon-ebadf-during-session.md). Individual listener graceful retirement and the other qualifications in the controller decision also remain open. This issue remains open.

---
<!-- id: issue-20261108-053553-856-b5-d3-carry-frozen-scoping-and-constrain-reviewed-  file: issue-20261108-053553-856-b5-d3-carry-frozen-scoping-and-constrain-reviewed-.md -->
