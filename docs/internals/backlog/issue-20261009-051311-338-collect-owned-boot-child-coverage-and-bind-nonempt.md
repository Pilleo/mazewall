---
title: "Collect owned Boot child coverage and bind nonempty kernel coverage classes"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "build.gradle.kts"
target_symbols:
  - "jacocoCombinedReport"
open_questions: false
---

# 🟡 [Severity: HIGH]: Collect owned Boot child coverage and bind nonempty kernel coverage classes

**Context:**
Exact full build enforcer unit coverage39523/50027=0.790033 below unchanged0.82 with1051 fresh unit tests (0 failures,2 skips). Existing18 passing Boot scenarios run child JVMs without JaCoCo, so NestedZipTable981 and BootArtifact665 missed instructions are uncollected; no new duplicate tests justified before measuring. Custom combined/kernel tasks have20 sessions but ZERO classes/counters because main output is unbound; their green status is vacuous. Retained maze-scanner-8 build/report evidence and independent read-only bytecode reconciliation487 matches0 mismatches rule out known stale filtered data.

**Needed:**
1. Declare exact test-harness/build plumbing scope; collect fresh explicitly owned Boot child exec data into unit report/verifier, bind existing custom combined/kernel coverage to actual main output, preserve all thresholds/exclusions/identity/sanitization/assertions and physical reaping, verify real nonempty denominators before selecting meaningful residual invariant tests.

---
<!-- id: issue-20261009-051311-338-collect-owned-boot-child-coverage-and-bind-nonempt  file: issue-20261009-051311-338-collect-owned-boot-child-coverage-and-bind-nonempt.md -->
