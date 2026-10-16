---
title: "Require parent startup authorization before Portal guest construction"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
target_symbols: 
  - "PortalWorkerMain"
open_questions: false
---

# 🟡 [Severity: HIGH]: Require parent startup authorization before Portal guest construction

**Context:**
P3 inspection confirms child connects then installs and constructs guests independently while parent attaches the cgroup after launch. Existing design claims attachment precedes construction, but no required parent exchange enforces it.

**Needed:**
1. Add a required parent control exchange before containment/guest construction and generation-bound readiness; verify delayed parent setup and startup cancellation.

**Parent-authorization facet (pending review; remaining P3 readiness still open):**
- Broker supplies fresh session/PID startup authorization only after parent setup succeeds. Worker must accept and acknowledge it before containment/guest construction. A legacy readiness sentinel cannot replace the required acknowledgement.
- Startup control frames have an independent 512-byte bound and consume the existing absolute startup deadline; they do not weaken application payload limits.
- Pure authorization checks and worker ordering/failure tests passed. Real child filter counts prove no additional process filter before parent authorization, then required installation before service use; wrong sessions and legacy-only startup reject and reap children.
- Missing contract baseline `run-6115447fb6bc`; focused host/native/quality `run-1be6bd26cfef`; fresh full host `run-87b7f35d556f`; fresh Docker `run-a7557f9c33a8` (three parent tests executed, zero skips/failures).
- Full artifact/manifest/policy-generation readiness, captured receipt reporting, parent-control identity and operator-owned launch profiles remain P3 acceptance, not implied by this parent permit/ack facet.

---
<!-- id: issue-20261007-214327-867-require-parent-startup-authorization-before-portal  file: issue-20261007-214327-867-require-parent-startup-authorization-before-portal.md -->
