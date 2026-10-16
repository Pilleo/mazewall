---
title: "Clear final candidate Detekt ZIP constants and Portal declaration naming findings"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/NestedZipTable.kt"
target_symbols:
  - "NestedZipTable"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Clear final candidate Detekt ZIP constants and Portal declaration naming findings

**Context:**
Exact e81e2c1e combined opt-in candidate fails detektWarningRatchet on ForbiddenSuppress MagicNumber in NestedZipTable and MatchingDeclarationName for PreparedPortalOperation.kt declaring PortalOperationCall. Remove prohibited suppression through semantic fixed ZIP constants, and rename only the Kotlin interface source file; preserve all validation behavior and public Java facade/API.

**Needed:**
1. Apply behavior-preserving constants/file rename in an isolated scope, run existing deployment-origin tests and module/aggregate Detekt ratchet, obtain independent review before integration.

**Reviewed implementation evidence (2026-10-09):**
Private `4b1913fd6cb426d42bed53ff80ef01de9fd6d569`, integrated as
`1ddf1b71e47b853524ba5de6455b479318584f8b`. Independent read-only review:
spec PASS, quality ACCEPT, no Critical/Important findings. All36 added semantic
ZIP constants reproduce baseline executable tokens;13 previous constants unchanged.
Kotlin interface source renamed byte-identically; Java facade and public ABI unchanged.
Focused27 deployment-origin tests, compile and module/aggregate Detekt GREEN;
zero added normalized findings across all six modules. Full unchanged private hook
passed after one preserved guard-enabled focused diagnosis and one authorized retry.
First hook ForeignFdGuard observation remains unresolved in issue20261009-014853-723.
No guard disabled/whitelisted, policy relaxed or native failure declared fixed.
Issue remains open until exact repository acceptance is reconciled.

---
<!-- id: issue-20261009-013010-665-clear-final-candidate-detekt-zip-constants-and-por  file: issue-20261009-013010-665-clear-final-candidate-detekt-zip-constants-and-por.md -->
