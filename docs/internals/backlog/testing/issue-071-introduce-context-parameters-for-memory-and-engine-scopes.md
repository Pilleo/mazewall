---
title: "Introduce Context Parameters for Memory and Engine Scopes"
severity: "ENHANCEMENT"
status: "open"
priority: 3
dependencies: []
component: "enforcer"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Introduce Context Parameters for Memory and Engine Scopes

**Target:** Entire `:enforcer` module
**Context:** Many methods pass `Arena` or `NativeEngine` as explicit parameters, leading to verbose method signatures and "parameter drilling."
**Needed:** Refactor internal kernel-interface methods to use Kotlin 2.0+ `context(Arena)` or `context(NativeFileSystem)`. This ensures that operations like path allocation or syscall execution are only possible within an active, valid context, reducing boilerplate and improving clarity.
