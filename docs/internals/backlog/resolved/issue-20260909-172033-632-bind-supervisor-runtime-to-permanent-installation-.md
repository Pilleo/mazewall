---
title: "Bind supervisor runtime to permanent installation and lease task registrations"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt"
target_symbols:
  - "SupervisorInstaller"
open_questions: false
---

# Resolved: Bind supervisor runtime to permanent installation and lease task registrations

**Context:**
Task session closes the permanent runtime; later registration targets another context and nested tasks lose their outer registration.

**Needed:**
1. Own listener resources for platform thread lifetime and issue reference-counted per-task leases.

**Resolution evidence (2026-09-09):** Permanent installation owners retain the runtime
and listener; task sessions are idempotent, reference-counted registration leases.
Owner termination requests reactor-owned socket/arena retirement. The final task
lease discards pending spawn authority without closing the reusable store. Tests
cover nested/sequential registrations, owner cleanup exactly once, and fresh-JVM
OPEN/OPENAT authorization across sequential/nested tasks without filter stacking.
Changing the authorization-policy object on the same permanent filter is explicitly
rejected and tested. Full enforcer and fresh-JVM SupervisorProxy gates passed.
Migration guidance, corrected wrapper KDoc, and diagrams accompany Task 3.

---
<!-- id: issue-20260909-172033-632-bind-supervisor-runtime-to-permanent-installation-  file: issue-20260909-172033-632-bind-supervisor-runtime-to-permanent-installation-.md -->
