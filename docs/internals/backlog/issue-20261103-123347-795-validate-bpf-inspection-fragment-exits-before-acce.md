---
title: "Validate BPF inspection fragment exits before accepting termination"
severity: "MEDIUM"
status: "open"
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
regression. The [upstream research](../research/bpf-upstream-validation.md#1-a-termination-token-does-not-prove-fragment-local-termination)
records the example and pinned gVisor fragment-outcome contract.

**Needed:**
1. Add an escaping-label regression, then require all matched fragment paths to return internally or reach explicitly permitted exits without weakening builder ownership or immutable termination prefixes.
2. Keep valid multi-branch inspections and existing ownership, stale-token and
   immutable-prefix checks passing. Run focused seccomp tests and the full build
   before recording implementation completion.

---
## Repository-review revalidation — 2026-11-04

[BPF-08](../reviews/2026-11-04-repository-review-findings.md#bpf-08) freshly compiles and
simulates the outer-label escape: structurally verified output returns ALLOW on the matched
path while an unreachable denial supplies termination. No current built-in inspection bypass
is established. Retain escaping-label and valid multibranch regressions; immutable BPF proof
and x32/payload fixes remain implemented and are not this fragment-local contract.

---
<!-- id: issue-20261103-123347-795-validate-bpf-inspection-fragment-exits-before-acce  file: issue-20261103-123347-795-validate-bpf-inspection-fragment-exits-before-acce.md -->
