---
title: "Assert installed no-new-privileges state in confined ancestry characterization"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperReadinessIntegrationTest.kt"
target_symbols:
  - "ConfinedHelperReadinessIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Assert installed no-new-privileges state in confined ancestry characterization

**Context:**
Whole-B4 build002 reproduced initial host NNP0 versus post-baseline NNP1, but the characterization expected initial NNP unchanged. Test-only correction requires caller NNP1. Subsequent focused host run reached strict unrelated mountViewDigest mismatch; qualified rootful008 passes all eight cases.

**Needed:**
1. Retain failed host and passing qualified evidence, preserve exact other ancestry/time comparisons, and include the assertion correction in final full-gate scope.

---
<!-- id: issue-20261106-021835-047-assert-installed-no-new-privileges-state-in-confin  file: issue-20261106-021835-047-assert-installed-no-new-privileges-state-in-confin.md -->
