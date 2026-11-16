---
title: "Preserve prepared resource close failures across repeat calls"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/PreparedOperationResources.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/PreparedPath.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/PreparedOperationResourcesTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/PreparedCloseOwnershipTest.kt"
target_symbols:
  - "PreparedOperationResources"
  - "PreparedPath"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve prepared resource close failures across repeat calls

**Context:**
Track the remaining close-outcome ownership gap as a distinct follow-up to [issue 338](issue-20261106-013649-338-retain-failed-prepared-acquisition-rollback-owners.md). Current accepted manager-owned capture behavior does not establish successful publication or global lifecycle completion. In PreparedPath.close, a native close result is followed by markClosed before the result is converted to an error; in PreparedOperationResources.retire, resource/artifact close errors are caught and merged, but a later call can observe phase CLOSED and return without surfacing retained Unknown history. Conversely, a native call that throws before markClosed may be dispatched yet replayed on a later close. Preserve the exact acquired token and Unknown/failure history across successful publication, claim and runtime retirement; do not infer a kernel cause or replay uncertain native close. New direct-owner staged work must include this case. This is source inspection only; no test or runtime reproduction is claimed.

**Needed:**
1. Trace actual typed close dispatch outcomes and owner phase transitions from PreparedPath through PreparedResourceSet, ImplementationArtifact and PreparedOperationResources into claim/runtime retirement; separate pre-dispatch from dispatched-Unknown without guessing.
2. Define retained failure/owner state that remains observable after repeated close and successful publication, preserving exact tokens and forbidding uncertain-dispatch replay; add focused owner tests with real descriptor ownership and injected call outcomes.

---
<!-- id: issue-20261109-110052-812-preserve-prepared-resource-close-failures-across-r  file: issue-20261109-110052-812-preserve-prepared-resource-close-failures-across-r.md -->
