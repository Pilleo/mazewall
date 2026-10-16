---
title: "Resolve pre-existing optional profiler detektTooling debt"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/build.gradle.kts"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Resolve pre-existing optional profiler detektTooling debt

**Context:**
Task7b optional detektTooling FAILED with 87 errors in 36 unchanged files and no source-transfer helper errors. This optional analysis is not the mandatory combined build gate; no rules, baselines or gate conditions were waived. Retain the failure as unresolved debt after Task6b.

**Needed:**
1. Review the retained Task7b fixround1-detekt-tooling.xml findings and resolve the 36-file tooling debt in a separately scoped change without suppressing rules or changing mandatory gate coverage.

---
<!-- id: issue-20261012-105213-824-resolve-pre-existing-optional-profiler-detekttooli  file: issue-20261012-105213-824-resolve-pre-existing-optional-profiler-detekttooli.md -->
