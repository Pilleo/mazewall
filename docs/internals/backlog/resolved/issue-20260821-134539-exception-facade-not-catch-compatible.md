---
title: "enforcer.ContainmentViolationException facade is not catch-compatible with the API type"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainmentViolationException.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/SandboxDispatcher.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/ContainmentViolationExceptionTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: enforcer.ContainmentViolationException facade is not catch-compatible with the API type

**Review (2026-08-21):** DUPLICATE of issue-20260821-000009-preserve-enforcer-package-classes (API exception now extends historical).

**Context:** A first attempt at `issue-20260821-000009-preserve-enforcer-package-classes` made the historical class extend the API class. That is the wrong direction for `catch`. See that issue for the JVM rule.

**Fix:** API exception extends the historical class. Library still throws the API type. `catch (io.mazewall.enforcer.ContainmentViolationException)` matches. `SandboxDispatcher` facade lives in the old package and forwards to the API object.

**Needed:**
1. API type is a subtype of the historical catch type (not the reverse).
2. Same `(String, Throwable?)` constructors.
3. Tests: throw API exception, catch historical type; call legacy `SandboxDispatcher.execute` / `shutdownAll`.
