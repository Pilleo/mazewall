---
title: "Add framework-agnostic portal service manifest"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/Portal.kt"
target_symbols:
  - "Portal"
open_questions: false

paperclip_issue_id: "d9616795-289f-4fa7-b15b-482ba08d7fbe"
paperclip_identifier: "MAZ-1470"
---

# 🟡 [Severity: MEDIUM]: Add framework-agnostic portal service manifest

**Context:**
Portal callers currently own ProcessBroker setup and worker registrations; service metadata must become explicit before adding an ergonomic runtime without loading guest code in the host.

**Needed:**
1. Define and test immutable manifest and service-id invariants for local process portals.

---
<!-- id: issue-20260913-150350-732-add-framework-agnostic-portal-service-manifest  file: issue-20260913-150350-732-add-framework-agnostic-portal-service-manifest.md -->
