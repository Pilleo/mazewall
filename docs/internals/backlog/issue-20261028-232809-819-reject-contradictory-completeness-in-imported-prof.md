---
title: "Reject contradictory completeness in imported profile documents"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfileDocument.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilingCoverage.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilingResult.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/BillOfBehavior.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/ProfileDocumentTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilingCoverageTest.kt"
target_symbols:
  - "decodeCoverage"
open_questions: false
---

# 🟡 [Severity: HIGH]: Reject contradictory completeness in imported profile documents

**Context:**
Host-only reproduction: serialize USER_NOTIF coverage with droppedEvents=3 and drainComplete=false, then change only integrity.complete from false to true. ProfileDocument accepts it and dedicatedWorkerPoliciesFromDocument produces policies. ProfilingCoverage.complete trusts the independent integrity flag. This is contradictory-input acceptance, not a claim that unsigned documents authenticate their producer.

**Needed:**
1. Validate completeness against persisted loss, drain, path and collector facts at the shared construction/import boundary; preserve conservative incomplete evidence. Add regressions for contradictory complete claims, valid round trips and legacy rejection. Inspect ProfilingCoverage and ProfilingResult conversion callers.

**Design-review follow-up (2026-11-03, finding 1):**
- Source inspection still finds independent `integrity.complete` import in `ProfileDocument.kt:120-144` and a Boolean-based projection in `ProfilingCoverage.kt:94-106,185-192`; the sealed `ProfileEvidence` facade alone does not validate those facts.
- Use one pure construction validator for completeness and its supporting facts. Preserve explicitly incomplete evidence and the always-incomplete HotSpot collector contract; successful validation is consistency evidence, not producer authentication or future-path coverage.
- The regression must mutate only the complete flag on an otherwise incomplete document and assert rejection before policy conversion, while retaining valid complete/incomplete round trips.

---
## Repository-review revalidation — 2026-11-04

[EVID-01](../reviews/2026-11-04-repository-review-findings.md#evid-01) reproduces the
single-bit contradiction against current compiled source and reaches dedicated-worker policy
conversion. Include unknown syscall names discarded by BoB import in the lossy-input matrix;
do not certify a lossy decoder merely because the remaining facts are consistent. Static
worker policy composition remains independent. This fix precedes EVID-03 ownership binding;
the separate five-overload profiler omission PN05-02 also requires correction.

---
<!-- id: issue-20261028-232809-819-reject-contradictory-completeness-in-imported-prof  file: issue-20261028-232809-819-reject-contradictory-completeness-in-imported-prof.md -->
