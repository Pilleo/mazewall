---
title: "Add negative controls for benchmark prepared health guards"
severity: "LOW"
status: "resolved"
priority: low
dependencies: []
component: "benchmarks"
target_modules: []
target_files:
  - "benchmarks/integration-boundaries/src/main/kotlin/io/mazewall/benchmark/Boundaries.kt"
target_symbols:
  - "preparedHealth"
open_questions: false
---

# 🟡 [Severity: LOW]: Add negative controls for benchmark prepared health guards

**Context:**
Final scoped review Minor M1: correct health guards for instance mode, policy profile, positive singleton PID and STARTED lifecycle lack independent one-field negative controls. Existing raw candidate617 receipts remain valid; this is regression coverage debt.

**Needed:**
1. Add four one-field invalid health records to the existing non-native selectioncheck; require each to reject while retaining both-order positive group selection and equal-duration fresh timing acceptance.

**Acceptance:** All four individual guard removals would fail the selection check. Existing valid health records pass. No native benchmark replay or candidate/artifact relabel is needed for this host-only regression change.

## 2026-10-19 source and mutation qualification

**Resolution:** the controls were already implemented; the source-bound positive
run and all four independent guard-removal failures establish this item's stated
host-only acceptance. No additional benchmark code was necessary.

All four controls were already present in `Boundaries.kt:162–169`; adding another
test matrix would duplicate them. Qualified the unchanged source SHA-256
`137e3536bf187f2ba3db658cd8ed43a73424c39e1a39f61cb39698ef7c4cf2f7`
with the already cached Kotlin2.4.20 compiler and actual published public types
and manifests. The baseline `--selectioncheck` exits0, retaining both-order
positive group selection and fresh equal-duration timing acceptance.

Independent copies removing only one guard each compile successfully and exit1
at the corresponding negative control:

| Removed guard | Required failure |
|---|---|
| Health instance mode equality | `Selection control accepted health mode mismatch` |
| Policy profile equality | `Selection control accepted health policy mismatch` |
| Positive worker PID | `Selection control accepted nonpositive PID` |
| STARTED lifecycle state | `Selection control accepted worker not started` |

Raw source/compiler/JAR hashes, full argv, logs and exit statuses are retained
under `.superpowers/sdd/2026-10-19-benchmark-health-controls/`, including
`input-manifest.json`, `compiler-hashes.json` and `mutation-summary.json`.
Original benchmark source and strict locks are unchanged.

**Identity/scope:** this host-only qualification uses the existing local publication
`0.1.0-thread-task3.c1bfd8a12b61.288b844cceb6`, not P617. Its seven first-party
copied JARs were checked byte-for-byte against that identified publication; the
consumer executable JAR was excluded. Direct compiler invocation avoids any
Gradle/source-project substitution. No native benchmark mode, throughput run,
fresh dependency-resolution claim, P617 relabel or change to historical locks
is part of this evidence. The original benchmark publication/measurement
acceptance remains separate.

---
<!-- id: issue-20261013-005143-547-add-negative-controls-for-benchmark-prepared-healt  file: issue-20261013-005143-547-add-negative-controls-for-benchmark-prepared-healt.md -->
