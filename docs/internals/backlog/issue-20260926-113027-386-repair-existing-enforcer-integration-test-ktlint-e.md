---
title: "Repair existing enforcer integration-test ktlint errors blocking clean rootful gates"
severity: "MEDIUM"
status: "open"
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
A clean Tier E rootful source-volume checkout with --rerun-tasks fails :enforcer:ktlintIntegrationTestSourceSetFormat on pre-existing wildcard imports and indentation errors in 49 integration-test files (2,182 reported findings). The gate retains its runner/workspace as INCOMPLETE. Repair requires its own scoped review; do not bypass the clean-checkout lint task or treat local build-cache success as a rootful pass.

**Needed:**
1. In a scoped enforcer change repair the integration-test source format without changing test behavior, rerun :enforcer:integrationTest --rerun-tasks and then rerun the Kotlin rootful gate.

---
<!-- id: issue-20260926-113027-386-repair-existing-enforcer-integration-test-ktlint-e  file: issue-20260926-113027-386-repair-existing-enforcer-integration-test-ktlint-e.md -->
