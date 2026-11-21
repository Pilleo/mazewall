---
title: "Validate BPF inspection fragment exits before accepting termination"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "core"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfBuilder.kt"
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/BpfVerificationBoundaryTest.kt"
target_symbols:
  - "BpfBuilder"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate BPF inspection fragment exits before accepting termination

**Context:**
Source review found that `expect` only checks a current same-builder `Terminated`
token; a body can jump to an outer label and append an unreachable RET, escaping
its inspection while the whole program remains structurally valid. Current
built-in inspectors use local exits. This is source-confirmed, not a newly executed
regression. The [upstream research](../../research/bpf-upstream-validation.md#1-a-termination-token-does-not-prove-fragment-local-termination)
records the example and pinned gVisor fragment-outcome contract.

**Needed:**
1. Add an escaping-label regression, then require all matched fragment paths to return internally or reach explicitly permitted exits without weakening builder ownership or immutable termination prefixes.
2. Keep valid multi-branch inspections and existing ownership, stale-token and
   immutable-prefix checks passing. Run focused seccomp tests and the full build
   before recording implementation completion.

---
## Repository-review revalidation — 2026-11-04

[BPF-08](../../reviews/2026-11-04-repository-review-findings.md#bpf-08) freshly compiles and
simulates the outer-label escape: structurally verified output returns ALLOW on the matched
path while an unreachable denial supplies termination. No current built-in inspection bypass
is established. Retain escaping-label and valid multibranch regressions; immutable BPF proof
and x32/payload fixes remain implemented and are not this fragment-local contract.

---
## Resolution Evidence (2026-11-21)

- Scoped `BpfLabel` (`enforcer/src/main/kotlin/io/mazewall/seccomp/BpfBuilder.kt`, shared with `:lockdown` via `PrepareLockdownCoreSources`) to `(owner, scopeId)` so labels created outside an `expect(...)` inspection fragment cannot be jumped to or marked inside it, and fragment-local labels cannot leak across the `expect(...)` boundary.
- Updated `BpfBuilder.Active.expect(...)` to validate the isolated fragment (`compile(ops.subList(fragmentStart, fragmentEnd))`, `BpfStaticVerifier.verify`, and `requireAllInstructionsReachable`) so matched paths must terminate internally without dead/unreachable `RET` tail tokens.
- Guarded empty `ArgCheck.EqualsAny` / `ArgCheck.EqualsAny32` lists in `BpfFilter.emitInspections` (`enforcer/src/main/kotlin/io/mazewall/enforcer/internal/seccomp/BpfFilter.kt`) so empty allowlists emit `ret(ifNotMatchedNative)` directly without an unreachable `allowLabel` tail.
- Added regressions in `enforcer/src/test/kotlin/io/mazewall/seccomp/BpfVerificationBoundaryTest.kt` covering the `BPF-08` outer-label escape, conditional escape jumps, outer-label `mark`, leaked inner `jmp`/`mark`, unreachable internal `RET` tails, and valid multi-branch local inspections.
- Verified sequentially: `./gradlew :enforcer:ktlintCheck :lockdown:ktlintCheck :enforcer:detektMain :lockdown:detektMain :detektWarningRatchet :enforcer:checkKotlinAbi :lockdown:checkKotlinAbi :verifySupportedApiSurface :verifyPackageTopology --no-daemon --console=plain` (`65` tasks, passed), `./gradlew :enforcer:ktlintTestSourceSetCheck :enforcer:test --tests io.mazewall.seccomp.BpfVerificationBoundaryTest --tests io.mazewall.seccomp.BpfBuilderTest --tests io.mazewall.seccomp.BpfFilterTest --tests io.mazewall.seccomp.BpfFilterLabelTest :lockdown:test --no-daemon --console=plain` (`BpfVerificationBoundaryTest` `18`, `BpfBuilderTest` `10`, `BpfFilterTest` `32`, `BpfFilterLabelTest` `2`, `:lockdown:test` `17`, all `0` failed/`0` skipped).

- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).
---
<!-- id: issue-20261103-123347-795-validate-bpf-inspection-fragment-exits-before-acce  file: issue-20261103-123347-795-validate-bpf-inspection-fragment-exits-before-acce.md -->
