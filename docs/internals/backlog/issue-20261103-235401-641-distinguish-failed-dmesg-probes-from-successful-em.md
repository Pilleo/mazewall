---
title: "Distinguish failed dmesg probes from successful empty triage evidence"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/triage/DiagnosticTriageRunner.kt"
target_symbols:
  - "DiagnosticTriageRunner"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Distinguish failed dmesg probes from successful empty triage evidence

**Context:**
Repository-wide review, 2026-11-04: [EVID-09](../reviews/2026-11-04-repository-review-findings.md#evid-09). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/boundary:** reproduced diagnostics/error-contract defect; optional failure triage. **Tracking:** **NEW**; related resolved bounded-capture issues and active ambient-attachment issue do not track this loss of error evidence.

- `DiagnosticTriageRunner.kt:87–105` reads only stdout, does not check exit status and filters the returned text to audit/seccomp/landlock lines. `ManagedProcessCapture.kt:34–40,61–66` provides a separate bounded exit API, but this caller never uses it. Stderr is neither redirected nor captured.
- Trigger: `dmesg` exits 1 and writes “read kernel buffer failed: Operation not permitted” to stderr, a normal unprivileged condition. An isolated fake owned `dmesg` with exactly this behavior returns **the empty string**, not an unavailable/denied diagnostic. The inner generic “Capture timed out…” fallback is also filtered away.
- Effect: “probe denied/failed” becomes indistinguishable from “no relevant audit logs”, undermining failure diagnosis. It does **not** authorize containment or weaken a kernel policy.
- Correction: preserve probe success/failure separately; boundedly inspect exit status/stderr and filter only successful log output. Retain actual failure causes rather than relabeling every read failure as timeout. Preserve fatal-error propagation and self-only snapshot scope.
- Regression: denied exit with stderr, read timeout, ordinary I/O failure, and successful zero/matching log cases. Existing managed-capture tests check success/timeout ownership, not this consumer's error classification. **Independent optional-tooling correction.**

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235401-641-distinguish-failed-dmesg-probes-from-successful-em  file: issue-20261103-235401-641-distinguish-failed-dmesg-probes-from-successful-em.md -->
