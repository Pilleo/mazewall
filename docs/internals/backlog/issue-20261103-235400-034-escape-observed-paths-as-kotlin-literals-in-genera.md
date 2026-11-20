---
title: "Escape observed paths as Kotlin literals in generated policy DSL"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/BillOfBehavior.kt"
target_symbols:
  - "toDsl"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Escape observed paths as Kotlin literals in generated policy DSL

**Context:**
Repository-wide review, 2026-11-04: [EVID-04](../reviews/2026-11-04-repository-review-findings.md#evid-04). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/boundary:** reproduced code-generation defect; developer policy-export tooling. **Tracking:** **NEW**; no matching DSL escaping/injection issue found in active/resolved backlog or current reviews.

- `BillOfBehavior.kt:155–185` normalizes paths and inserts them directly between Kotlin double quotes. Normalization does not escape Kotlin syntax. `ProfilingResult.kt:98–103` exposes this through the coverage-qualified result API.
- Trigger: a valid Linux pathname containing `$`, a quote, backslash or newline. The actual generated line for the repro path is `.allowFsRead("/tmp/${error("from-path")}")` (shown as source, not JSON-escaped output). Copy-pasting it evaluates interpolation instead of using the recorded filename. Plain backslashes/quotes can also produce invalid or different literals.
- Effect: incorrect/noncompiling policies; with an attacker-controlled observed filename, code embedded in a string template can run when the operator compiles/executes the advertised copy-paste snippet. This requires that subsequent operator action; collection itself does not evaluate it.
- Correction: emit Kotlin string literals with Kotlin-aware escaping, including `$`, quotes, backslashes and control characters. A JSON-only escaper is insufficient for `$`.
- Regression: compile emitted snippets containing all special characters and assert that their resulting grants equal `toPolicy()`'s exact paths, with no interpolation/body side effect. Existing DSL tests use ordinary paths (`BillOfBehaviorTest.kt:224–255`, `BobCompilerTest.kt:31–88`). **Independent of coverage repair.**

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235400-034-escape-observed-paths-as-kotlin-literals-in-genera  file: issue-20261103-235400-034-escape-observed-paths-as-kotlin-literals-in-genera.md -->
