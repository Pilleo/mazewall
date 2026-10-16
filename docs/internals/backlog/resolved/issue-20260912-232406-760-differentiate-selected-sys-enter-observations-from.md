---
title: "Differentiate selected sys_enter observations from unobserved and seccomp-denied attempts"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/TierEProfile.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/AttributionCollector.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/AttributionCollectorTest.kt"
target_symbols:
  - "TierEProfile"
  - "ObservationScope"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Differentiate selected sys_enter observations from unobserved and seccomp-denied attempts

**Context:**
Tier E captures syscalls via a selected `sys_enter` raw tracepoint program. Syscall execution attempts blocked at earlier layers (such as seccomp filters rejecting before tracepoints or unprobed syscalls) are not captured by `sys_enter`. Reports must make the capture boundary explicit, distinguishing observed syscalls from execution attempts, and noting seccomp denial boundaries.

**Needed:**
1. Define explicit capture domain in `TierEProfile` / `SessionIntegrity`: record that observed events are `sys_enter` observations.
2. In attribution reporting, record boundary limitations so unobserved or denied attempts are explicitly categorized rather than implied as never occurring.
3. Add unit test:
   - Seccomp-denied attempt + active session: output includes boundary limitation and attempt-vs-observation distinction.

---
<!-- id: issue-20260912-232406-760-differentiate-selected-sys-enter-observations-from  file: issue-20260912-232406-760-differentiate-selected-sys-enter-observations-from.md -->
