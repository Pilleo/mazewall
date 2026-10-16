---
title: "Repair existing enforcer integration-test ktlint errors blocking clean rootful gates"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/ContainedExecutorsTest.kt"
target_symbols:
  - "ContainedExecutorsTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Repair existing enforcer integration-test ktlint errors blocking clean rootful gates

**Context:**
A clean Tier E rootful source-volume checkout with --rerun-tasks initially reported wildcard imports and indentation errors in 49 Kotlin files (2,182 findings). The transfer had omitted the tracked root `.editorconfig`, which disables those ktlint rules. The same enforcer ktlint task passed on the host with `.editorconfig` present. The gate correctly retained its runner/workspace as INCOMPLETE.

**Needed:**
1. Identify why the host ktlint configuration differs from the disposable rootful checkout; transfer its tracked policy file and rerun the clean Kotlin rootful gate without bypassing lint.

**Resolution:** Added `.editorconfig` to `TierERootfulSourceTransfer` with a
regression test; no 49-file formatting change was needed. The next clean
rootful gate passed enforcer ktlint and reached the fresh-JVM workflow test;
the following gate passed that test and reached the separate native phase
regression (issue-20260926-133258-892).

---
<!-- id: issue-20260926-113027-386-repair-existing-enforcer-integration-test-ktlint-e  file: issue-20260926-113027-386-repair-existing-enforcer-integration-test-ktlint-e.md -->
