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
paperclip_issue_id: "9c964560-08ba-4123-b83c-4167565c5c86"
paperclip_identifier: "MAZ-1392"
---

# 🔴 [Severity: LOW]: Architectural DIP (Dependency Inversion) Violations in Native Scopes

**Target:** Entire project
**Context:** Many classes directly instantiate `Arena.ofConfined()` or rely on the `LinuxNative` object, making isolated unit testing without a Linux kernel difficult.
**Needed:** Refactor components to accept `NativeEngine` or `NativeScope` as constructor dependencies, improving mockability and environment independence.
