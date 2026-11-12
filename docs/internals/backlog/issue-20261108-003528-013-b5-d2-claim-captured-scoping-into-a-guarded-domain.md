---
title: "B5 D2 claim captured scoping into a guarded domain"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/OwnedStackScopingBootstrap.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/ScopingPreparation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/FrozenStackScoping.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/PreparedLandlock.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedLiveScopingTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/ConfinedHelperRuntimeDrainTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/PreparedOperationResourcesTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  - "enforcer/api/enforcer.api"
  - "enforcer/src/test/kotlin/io/mazewall/landlock/PreparedLandlockRetainedTest.kt"
target_symbols:
  - "ManagedRuntime"
  - "OwnedStackScopingBootstrap"
  - "ScopingPreparation"
  - "FrozenStackScoping"
  - "OwnedDomain"
  - "PreparedLandlock"
  - "Landlock"
open_questions: false
---

# 🟡 [Severity: HIGH]: B5 D2 claim captured scoping into a guarded domain

**Context:**
Parent issue 359: deliver the accepted counted claim-to-guard unit after D1; retain typed Landlock ownership and exact origin through failure and drain.

**Needed:**
1. Deliver the complete accepted D2 claim-to-guard unit after controller release, using the current origin-gate Ready identity and one counted resource claim. Reject mismatched policy, limits, runtime or wrapper before consuming Ready.
2. Attach the borrowed Landlock shell before acquisition; immediately retain the original typed ruleset descriptor before any callback or allocation. Preserve exact owner, Pending/Unknown, failure identity and same-manager quiescence.
3. Record late wait failure on the existing transferred association and report it on the CLOSED drain fast path without reopening or repeating cleanup. Install the Live guard before helper setup, worker prestart or irreversible containment effects.
4. Execute behavioral RED/GREEN tests against the actual manager and real owned descriptors; verify focused suites, API/ABI, root warning ratchet and SpotBugs, then obtain an independently reviewed full build. No native/live activation, B6 release, dependency changes or qualification are implied.

Preparation: [D2 plan](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d2-claim-preparation072/plan-fixed1.md). Parent: issue 359. Existing D1 issue 759 remains open. The approved prepare overload and its exact generated API were reviewed before the bounded checkpoint.


**Bounded checkpoint:** The guarded D2 implementation and one full-build attempt are accepted with explicit limits. The [implementation report](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d2-claim-implementation073/report.md), [original review](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d2-claim-implementation073/independent-review.md), [F1/F2 review](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d2-claim-implementation073/fix2-independent-review.md), [controller decisions](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d2-claim-implementation073/controller-decisions.md), and [pre-full decision](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d2-claim-implementation073/controller-pre-full-decision.md) preserve the implementation evidence. The [074 build report](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d2-claim-full-build074/report.md), [independent terminal review](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d2-claim-full-build074/independent-review.md), and [controller decision](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d2-claim-full-build074/controller-decision.md) record 4,424 non-skipped executions, 45 skips and no failures; cached analysis and skipped cases retain their stated limits.

This issue remains **open**. The original clean CLOSED-before-first-late-failure qualification needs a legitimate successful Live preparation and is not waived. The Live guard still refuses before helper setup and worker effects; no JNI or packaged Ready, B6 dispatcher, live body or native activation is established. Three profiler shutdown exceptions remain unresolved, and individual process, descriptor and pump retirement is unknown under issue 752. Broader qualification and rollback limits remain open.

---
<!-- id: issue-20261108-003528-013-b5-d2-claim-captured-scoping-into-a-guarded-domain  file: issue-20261108-003528-013-b5-d2-claim-captured-scoping-into-a-guarded-domain.md -->
