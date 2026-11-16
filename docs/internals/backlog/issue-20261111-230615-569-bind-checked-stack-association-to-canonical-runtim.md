---
title: "Bind checked stack association to canonical runtime owner"
severity: "HIGH"
status: "open"
priority: high
dependencies:
  - "issue-20261111-224650-335-revalidate-checked-native-stack-association-at-pro"
component: "core"
target_modules: []
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntimeAcquisitionOwner.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/ScopingPreparation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/OwnedStackScopingBootstrap.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedStackScopingPackagedBridge.kt"
target_symbols:
  - "CanonicalAcquisition"
open_questions: false
---

# 🟡 [Severity: HIGH]: Bind checked stack association to canonical runtime owner

**Context:**
The canonical original owner captures baseline before a checked association is retained, so a later producer cannot use that original checked adapter with counted claim and retirement.

**Needed:**
1. Design and review the original-owner association and same-lock reserve claim with fail-closed original-owner retirement before implementation.


**Checkpoint (2026-11-12):** The original canonical start actor captures the checked association; the admitted producer actor performs checked revalidation, the locked claim and actual Reserve, with immediate original-owner publication and fail-closed unknown gates. Current controlled normal and six race/fault probes exercise the bounded paths described in the design. Focused tests/static checks passed on the current cohort. This item remains open pending the combined completion gate and review; explicit fixture binding is not natural readiness. See [live stack scoping design](../designs/enforcer/live-stack-scoping.md).

---
<!-- id: issue-20261111-230615-569-bind-checked-stack-association-to-canonical-runtim  file: issue-20261111-230615-569-bind-checked-stack-association-to-canonical-runtim.md -->


### Conditional full-build checkpoint — 2026-11-12

The [current full-build report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/issue338-full-gate-detailed-report002.md) records a successful full build with 4,672 cases, 45 skips and no failures or errors; unit instruction coverage was 80,145/97,726 (82.009905%) and combined coverage was 80,572/97,726 (82.446841%). The owning FreshJvm task used the approved 15,000 ms configuration; full-run supervisor propagation was not sampled. The ordinary five-second default, issue 051 startup cause and default reliability remain unqualified. Three shutdown socket exceptions and three suppressed directory exceptions leave issue 752 resource retirement Unknown. Issues 051, 338 and 569 remain open: pre-typed raw/token ownership, VM and arena gaps, canonical dispatcher and custody limits remain; B5 helper capture requires its next preimplementation review. This conditional checkpoint does not close B5 or the wider roadmap. Earlier pending statements describe their historical checkpoints and are superseded only as to this full-build result.
