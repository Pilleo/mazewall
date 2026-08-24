---
title: Introduce Context Parameters for Memory and Engine Scopes
severity: ENHANCEMENT
status: open
priority: low
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/NativeEngine.kt
target_modules:
- :enforcer
component: enforcer
effort: medium
paperclip_issue_id: ff2ab246-e708-4dd3-8a4c-19827c33b14a
---

# 🔵 [Severity: ENHANCEMENT]: Introduce Context Parameters for Memory and Engine Scopes

**Target:** Entire `:enforcer` module
**Context:** Many methods pass `Arena` or `NativeEngine` as explicit parameters, leading to verbose method signatures and "parameter drilling."
**Needed:** Refactor internal kernel-interface methods to use Kotlin 2.0+ `context(Arena)` or `context(NativeFileSystem)`. This ensures that operations like path allocation or syscall execution are only possible within an active, valid context, reducing boilerplate and improving clarity.
