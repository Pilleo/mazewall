---
title: "Encode legal portal state-effect pairs without interpreter casts"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMachine.kt"
target_symbols:
  - "PortalWorkerMachine"
open_questions: false

paperclip_issue_id: "781db677-2ef2-4a12-98cd-e2a71686f53f"
paperclip_identifier: "MAZ-1243"
---

# 🟡 [Severity: MEDIUM]: Encode legal portal state-effect pairs without interpreter casts

**Context:**
PortalWorkerMachine is pure but returns a broad PortalWorkerEffect union. PortalWorkerMain must use as? Dispatch and repeated as Send casts, so legal state-event-effect relationships are only asserted at runtime.

**Needed:**
1. Model transition outcomes by legal interpreter action or provide typed transition entry points, and remove unchecked effect casts from PortalWorkerMain.

---
<!-- id: issue-20260907-232023-285-encode-legal-portal-state-effect-pairs-without-int  file: issue-20260907-232023-285-encode-legal-portal-state-effect-pairs-without-int.md -->
