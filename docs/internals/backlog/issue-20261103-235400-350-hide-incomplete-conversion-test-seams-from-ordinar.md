---
title: "Hide incomplete-conversion test seams from ordinary Java consumers"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilingResult.kt"
target_symbols:
  - "toPolicyAllowingIncomplete"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Hide incomplete-conversion test seams from ordinary Java consumers

**Context:**
Repository-wide review, 2026-11-04: [EVID-05](../reviews/2026-11-04-repository-review-findings.md#evid-05). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/boundary:** reproduced public-API contract defect; Java profiler consumers, not a hostile-code security boundary. **Tracking:** **NEW**; related historical incomplete-DSL issues were inspected but do not cover Java visibility.

- `BillOfBehavior.kt:94–100,147–153` and `ProfilingResult.kt:92–110` mark bypasses Kotlin `internal` while assigning unmangled `@JvmName("toPolicyAllowingIncomplete"/"toDslAllowingIncomplete")`; they are not JVM-synthetic. The documented promise is “There is no public allowIncomplete override” (`profiler/README.md:119–122`).
- Trigger/effect: normal `javac` code, with no reflection or Kotlin friend access, calls `new BillOfBehavior().toPolicyAllowingIncomplete(..., ProfilingCoverage.absent())` successfully while public `toPolicy()` rejects the same evidence. The lane's Java repro confirms it. Existing Java tests themselves use these seams (`ProfilerJavaApiTest.java:102,112–118`). Kotlin API dumps need not expose an `internal` method, so their omission does not prove Java invisibility.
- Correction: hide test-only seams from Java source (or move them behind the test/friend surface) and migrate Java tests to public refusal/qualified-result contracts. Decide explicitly whether any existing Java callers are supported before removing/changing descriptors. Hiding them is API hygiene, not protection from reflection or arbitrary policy construction.
- Regression: an independent Java consumer must not compile a call to a test-only bypass; legitimate public conversion overloads remain usable. **Independent of consistency and behavior binding.**

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235400-350-hide-incomplete-conversion-test-seams-from-ordinar  file: issue-20261103-235400-350-hide-incomplete-conversion-test-seams-from-ordinar.md -->
