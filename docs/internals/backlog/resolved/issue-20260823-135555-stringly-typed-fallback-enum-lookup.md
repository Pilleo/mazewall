---
title: "Stringly-Typed FallbackBehavior Enum Lookup in Failure Path"
severity: "MEDIUM"
status: "resolved"
priority: medium
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟠 [Severity: MEDIUM]: Stringly-Typed FallbackBehavior Enum Lookup in Failure Path

**Context:** `ContainedExecutors.installInternal` catch block
(enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt:277) compares fallback behavior
using `Platform.FallbackBehavior.valueOf("WARN_AND_BYPASS")` instead of the enum constant reference.
This is inside the security-critical installation failure path. Renaming the enum constant would not be
caught at compile time here — `valueOf` throws `IllegalArgumentException` at runtime, which would escape
from the `catch (t: Throwable)` handler and mask the original installation failure with a confusing
secondary exception. The comparison is also redundant: the enclosing `if` already established
`fallback != FAIL`, so a plain enum equality check suffices.

**Needed:**
1. Replace `fallback == Platform.FallbackBehavior.valueOf("WARN_AND_BYPASS")` with
   `fallback == Platform.FallbackBehavior.WARN_AND_BYPASS`.
2. Add a unit test exercising the `WARN_AND_BYPASS` failure path (e.g. via `MockNativeEngine` fault
   injection) asserting the warning is logged and a non-installed `InstallationReceipt` is returned.

