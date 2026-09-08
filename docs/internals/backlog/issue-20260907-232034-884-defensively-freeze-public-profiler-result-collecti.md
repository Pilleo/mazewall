---
title: "Defensively freeze public profiler result collections"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
target_symbols:
  - "BillOfBehavior"
open_questions: false

paperclip_issue_id: "5dd96592-2412-4477-9729-b19d95ad8ebd"
paperclip_identifier: "MAZ-1245"
---

# 🟡 [Severity: MEDIUM]: Defensively freeze public profiler result collections

**Context:**
BillOfBehavior is documented as immutable but its public data-class constructor retains caller-supplied sets and stackProfile map. ProfilingResult does the same for its public map/list fields, and stack profile exposes mutable Array values. A caller can mutate behavior or result contents after construction, including inputs used by toPolicy().

**Needed:**
1. Replace data-class storage or add factories/init copies that deeply freeze collection boundaries; define an immutable stack-frame representation or clone arrays on ingress and egress.

---
<!-- id: issue-20260907-232034-884-defensively-freeze-public-profiler-result-collecti  file: issue-20260907-232034-884-defensively-freeze-public-profiler-result-collecti.md -->
