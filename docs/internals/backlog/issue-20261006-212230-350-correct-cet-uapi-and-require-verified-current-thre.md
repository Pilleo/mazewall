---
title: "Correct CET UAPI and require verified current-thread shadow-stack locking"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["platform", "enforcer"]
target_files: 
  - "platform/src/main/kotlin/io/mazewall/ffi/NativeConstants.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/state"
  - "enforcer/src/test"
  - "enforcer/src/integrationTest"
  - "enforcer/api/enforcer.api"
  - "tests/consumers"
  - "enforcer/README.md"
  - "docs/internals/designs"
  - "docs/internals/intel-cet-jvm-sandbox-integration.md"
  - "docs/internals/research/intel-cet-legacy-notes.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Correct CET UAPI and require verified current-thread shadow-stack locking

**Context:**
Verified Linux headers/source define SHSTK_STATUS as 0x5005, not unlock 0x5004. Existing CET setup can bypass requested protection, accept lock EPERM as proof, and late-enable shadow stack across an ordinary FFM call-return boundary.

**Needed:**
1. Add independent operation-code and fail-closed CET regressions; require pre-enabled current-thread SHSTK, successful lock and status verification, retain evidence/progress, and reject unsupported process-wide coverage.

## Confirmed contract and inline correction evidence

- Base revision `e6be7f03`. The operator explicitly resumed original-plan execution inline, without further subagents/worktrees.
- Independently verified repository status value `0x5004` against the host Linux UAPI header: `UNLOCK=0x5004`, `STATUS=0x5005`. The independent status-operation test failed before the correction; it expects literal UAPI values rather than deriving expectations from the implementation constant.
- Verified primary Linux shadow-stack documentation, Linux v6.6 `shstk_prctl`, and the kernel shadow-stack selftest. Status reports enabled bits; LOCK returns zero and ORs the lock mask. Enabled status is not lock proof, and the selftest explicitly avoids a normal function-call return across enablement.
- Removed late FFM ENABLE. CET now verifies a pre-enabled current platform thread, requires LOCK success exactly zero, then verifies enabled status. EPERM/EACCES and unexpected success values cannot confirm protection. Native status permission/protocol failures retain their identity instead of becoming inactive status.
- Receipts expose `CetOutcome` and required unpack checks it; failed CET setup cannot authorize task entry under manual fallback. Progress records a newly successful CET lock before later validation/diagnostic stages. Failed installation remains quarantined, and existing registration closure remains its sole lifecycle owner.
- Thread/backend-bound proof is retained in existing immutable installation evidence and published by the closed registry-effect interpreter. Repeat installation reuses proof without attempting another lock or masking permission failure. Other threads establish fresh proof; process-state merging and late publication do not erase or promote thread proof.
- Process-wide CET requests reject before irreversible work because current-thread controls cannot establish sibling coverage. Historical unsafe JVM/IBT/process-wide guidance is retained as explicitly withdrawn research; the current guide documents the narrower contract and verified primary sources.
- Sixteen CET enforcement/proof cases, the independent UAPI operation test, existing CET/registry/architecture host tests, full enforcer/platform/profiler suites, ABI/Java constructor migration, and unchanged Detekt warning ratchet passed.
- `./gradlew build integrationTest integrationTestFreshJvm` passed: 272 tasks, 97 executed, 175 up-to-date. Full caller-mapped Docker gate passed in 1m11s. Fresh packaged Java/Kotlin consumer/scope compiles and dependency-lock refresh passed.
- Native CET report: three cases, two passed and one explicit prerequisite skip. Readonly status agrees with current-thread proc evidence and an inactive runtime rejects required CET without late enablement. Positive native locking acceptance skipped because this runtime/loader has not enabled SHSTK; no positive native CET protection/release claim is made from that skip.
- The demonstrated CET correctness defects are corrected. Application-owned capability/native runtime capture, physical worker/generation retirement, and positive CET acceptance on a compatible pre-enabled runtime remain separately gated original-plan work. This issue stays open for repository review.

---
<!-- id: issue-20261006-212230-350-correct-cet-uapi-and-require-verified-current-thre  file: issue-20261006-212230-350-correct-cet-uapi-and-require-verified-current-thre.md -->
