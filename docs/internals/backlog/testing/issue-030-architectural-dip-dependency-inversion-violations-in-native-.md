---
title: Architectural DIP (Dependency Inversion) Violations in Native Scopes
severity: HIGH
status: open
priority: low
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/NativeEngine.kt
target_modules:
- :enforcer
component: enforcer
effort: large
paperclip_issue_id: 09d237f2-84dc-4a0b-9688-b00ec5b1239b
paperclip_identifier: MAZ-753
---

# 🔴 [Severity: LOW]: Architectural DIP (Dependency Inversion) Violations in Native Scopes

**Target:** Entire project
**Context:** Many classes directly instantiate `Arena.ofConfined()` or rely on the `LinuxNative` object, making isolated unit testing without a Linux kernel difficult.
**Needed:** Refactor components to accept `NativeEngine` or `NativeScope` as constructor dependencies, improving mockability and environment independence.
