---
title: "Classify remote document limits as invalid Koog input"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/koog-document-agent/src/main/kotlin/demo/koog/SafeToolFailures.kt"
target_symbols:
  - "toolFailureKind"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Classify remote document limits as invalid Koog input

**Context:**
Approved inline follow-up Task3. A516-byte129-heading document reaches worker and fails its limit, but completed remote IllegalArgumentException becomes INTERNAL_FAILURE. Map known trusted remote validation type to INVALID_INPUT, retain original/local safe categories, no message parsing or broader unknown-type mapping.

**Needed:**
1. Write host classifier RED and actual generated-Portal invalid-then-valid regression, minimal remote-type mapping, marker/report-count/docs updates; verify11host/3native then final source-bound/full gates.

---
<!-- id: issue-20261030-075255-456-classify-remote-document-limits-as-invalid-koog-in  file: issue-20261030-075255-456-classify-remote-document-limits-as-invalid-koog-in.md -->
