---
title: "Make portal frames and machine payloads deeply immutable"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/PortalFrame.kt"
target_symbols:
  - "PortalFrame"
open_questions: false

paperclip_issue_id: "5e0e5524-7608-4fc9-8de8-b2162300b15a"
paperclip_identifier: "MAZ-1242"
---

# 🟡 [Severity: MEDIUM]: Make portal frames and machine payloads deeply immutable

**Context:**
PortalFrame publicly exposes a mutable ByteArray and PortalBrokerCall/PortalWorker state-machine events and effects retain those arrays. Mutation after construction can change an in-flight request or reply without a state transition; Kotlin data-class equality is reference-based for arrays.

**Needed:**
1. Introduce a defensively copied immutable byte payload boundary (without adding dependencies), then make frame and effect constructors/accessors preserve it.

---
<!-- id: issue-20260907-232023-077-make-portal-frames-and-machine-payloads-deeply-imm  file: issue-20260907-232023-077-make-portal-frames-and-machine-payloads-deeply-imm.md -->
