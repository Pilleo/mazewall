---
title: "Compile-Time Feature Proof Tokens and Scope-Safe Policy Builders (Type-State Pattern)"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "unknown"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Compile-Time Feature Proof Tokens and Scope-Safe Policy Builders (Type-State Pattern)

**Context:** Currently, `ContainedExecutors.kt` throws a runtime `UnsupportedOperationException` if process-wide containment is applied with Landlock rules because Landlock has historically been considered thread-scoped only. However, process-wide Landlock is supported on some newer kernels/setups. Blocking it unconditionally at compile-time or throwing runtime failures limits support on modern systems.
**Needed:** Implement compile-time feature proof tokens and type-state parameterized builders.
1. Define a `ProcessWideLandlockToken` that can only be obtained at runtime by checking support (`Landlock.isSupportedProcessWide()`).
2. Parameterize `PolicyBuilder` with a `Scope` type-state, requiring the token to configure Landlock filesystem rules on a process-wide policy.
3. Implement a `LandlockFallback` enum (`FailClosed`, `WarnAndBypass`) for process-wide policy installations when runtime kernel support is absent.
4. This ensures that Landlock's conditional process-wide availability is verified at runtime before configuration, preventing illegal rulesets while preserving compilation safety.
