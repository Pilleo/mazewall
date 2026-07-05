---
title: "Contract-Based Invariant Validation"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "enforcer"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Contract-Based Invariant Validation

**Target:** `io.mazewall.Platform.kt`, `io.mazewall.enforcer.ContainerStateRegistry.kt`
**Context:** We perform many runtime checks for thread types (e.g., ensuring not on a Virtual Thread) and platform support.
**Needed:** Use `kotlin.contracts` to define formal invariants. For example, a `validateNotVirtual()` function should use a contract to prove to the compiler that the following code is safe from Loom-specific carrier poisoning, allowing for more aggressive smart-casting and reduced redundant checks.
