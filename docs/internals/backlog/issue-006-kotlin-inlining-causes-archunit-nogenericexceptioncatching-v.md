---
title: "Kotlin Inlining Causes ArchUnit noGenericExceptionCatching Violation"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Kotlin Inlining Causes ArchUnit noGenericExceptionCatching Violation

**Context:** Kotlin inline functions like `Arena.ofConfined().use { ... }` or `nativeScope` expand at compilation time to copy their internal `try ... catch (e: Throwable)` or `finally` blocks directly into caller methods. As a result, caller methods in un-excluded packages like `io.mazewall.enforcer.supervisor` are falsely reported by ArchUnit as catching generic `Throwable` or `Exception` (e.g. `connectWithRetry` or `handleInjectFd`), violating the strict `noGenericExceptionCatchingInEnforcer` rule.
**Needed:** Add supervisor classes (e.g. `SupervisorSessionHandler`, `SupervisorInstaller`) to the ArchUnit exclusions list in `ArchitectureTest.kt` for this rule, as they do not catch generic exceptions directly but rely on standard FFM scoped block helpers.
