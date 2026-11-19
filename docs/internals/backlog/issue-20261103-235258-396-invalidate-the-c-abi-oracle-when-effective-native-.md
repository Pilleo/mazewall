---
title: "Invalidate the C ABI oracle when effective native build inputs change"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: [":platform"]
target_files:
  - "platform/build.gradle.kts"
target_symbols:
  - "compileCAbiLayoutOracle"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Invalidate the C ABI oracle when effective native build inputs change

**Context:**
Repository-wide review, 2026-11-04: [TEST-05](../reviews/2026-11-04-repository-review-findings.md#test-05). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category:** confirmed missing cache inputs; qualification gap, **not** a reproduced ABI mismatch. **Confidence:** high for missing dependencies, medium for deployment impact. **Release boundary:** platform/shared FFM ABI assurance. **Status:** NEW bounded cache-validity issue.

**Trigger/evidence:** Run platform tests, then change the installed header/compiler/include environment while retaining the checkout outputs. `platform/build.gradle.kts:57–74` declares only `layout_oracle.c` as the compile input and the binary as output; the actual compiler, included C/UAPI headers and environment are absent. The 40-line source includes many system headers and conditionally emits fields based on Landlock header macros (`platform/src/test/resources/abi/layout_oracle.c:2–12,30–36`).

`CAbiLayoutOracleTest.kt:17–33` claims comparison against **the test host's installed C headers**, but executes that already-built binary. The ordinary cacheable `test` only depends on compilation (`platform/build.gradle.kts:83–85`); the binary is not declared as a Test input. Reusing a previously compatible executable or host Test result after a header/toolchain change therefore need not re-observe the current ABI oracle. The freshness convention for `integrationTest*` does not cover this ordinary host test.

**Concrete gate escape:** A newly selected header changes an emitted size/offset/constant, but unchanged `.c` and retained outputs leave the old executable/old passing test valid to Gradle. The passing test then compares the old header inventory, not the current environment. This is a cache-key defect, not evidence that any currently supported Linux ABI has changed or that native installation is bypassed.

**Smallest complete fix:** For this tiny oracle, unconditionally compile it and declare the resulting binary as an input to the comparator Test, or fingerprint its effective compiler/preprocessed headers/environment. Keep portable native-output caching disabled. The existing CET fixture already has the relevant fingerprinting pattern; no new dependency is necessary.

**Regression/qualification:** In a disposable ordinary-user fixture, change an owned included header/compiler wrapper without changing `.c`; require oracle regeneration and comparator invalidation. Unchanged inputs may retain intended host reuse, but the changed ABI observation must not be admitted as old success. The build/CI lead owns actual Gradle execution.

**Mapping/dependencies:** No active/resolved C-ABI-oracle cache issue found. Adjacent `issue-20261007-162843-691-reuse-unchanged-cet-fixture-compilation-with-compl.md` explicitly fingerprints compiler/headers/linker/environment for a different native fixture. This is not a rediscovery of `issue-20261007-221224-904...`: its fresh integration Test policy is already present and does not reach this host oracle.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235258-396-invalidate-the-c-abi-oracle-when-effective-native-  file: issue-20261103-235258-396-invalidate-the-c-abi-oracle-when-effective-native-.md -->
