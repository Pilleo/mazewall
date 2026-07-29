---
title: "Unify hardcoded Seccomp notification offsets in Profiler and Supervisor via centralized FFM Layouts"
severity: "HIGH"
status: "open"
priority: 8
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/SeccompNotificationParser.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerConstants.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Unify hardcoded Seccomp notification offsets in Profiler and Supervisor via centralized FFM Layouts

**Context:**
Both `ProfilerConstants.kt` (used by `:profiler`'s `SeccompNotificationParser`) and `SupervisorSessionHandler.kt` (used by `:enforcer`'s supervisor session handler) define duplicate, hardcoded long offsets for reading/writing seccomp notification and response structures. For example:
- `NOTIF_ID_OFF = 0L`
- `NOTIF_PID_OFF = 8L`
- `NOTIF_ARCH_OFF = 20L`
- `NOTIF_NR_OFF = 16L`
- `NOTIF_ARGS_OFF = 32L`
- `RESP_ID_OFF = 0L`
- `RESP_VAL_OFF = 8L`
- `RESP_ERR_OFF = 16L`
- `RESP_FLAGS_OFF = 20L`

**The Issue:**
This violates the project's central design guideline of using FFM layouts (`Layouts.kt`) to resolve byte offsets dynamically, introducing the risk of ABI misalignment or structural drift if alternative architectures are targeted or if kernel layouts change. It also creates unnecessary duplication across the codebase.

**Needed:**
1. Leverage `Layouts.SECCOMP_NOTIF` and `Layouts.SECCOMP_NOTIF_RESP` in both `SupervisorSessionHandler` and `RealSeccompNotificationParser` to calculate member offsets dynamically via `byteOffset()` rather than hardcoding constants.
2. Deprecate and remove duplicate offset definitions in `ProfilerConstants.kt` and `SupervisorSessionHandler.Companion`.
3. Verify structural correctness via unit tests using `Layouts` alignment validation.
