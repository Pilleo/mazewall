---
title: "Enforce process incarnation identity gating across PID reuse and exec transitions"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/InvocationReplayMachine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ControlSessionMachine.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/InvocationReplayMachineTest.kt"
target_symbols:
  - "InvocationReplayMachine"
  - "ProcessIncarnation"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Enforce process incarnation identity gating across PID reuse and exec transitions

**Context:**
Linux PIDs are recycled. If a target JVM exits or invokes `execve` and another process receives the same PID, stale task identities or buffered events must never be attributed to the old session. Similarly, an active session must reject events whose process incarnation or start time does not match the session registration.

**Needed:**
1. In `InvocationReplayMachine`, validate incoming `TaskIdentity.processEpoch` against the registered `ProcessIncarnation`. Events with an epoch mismatch must immediately invalidate task state and fail attribution (`ACTIVATION_MISMATCH` / `SEQUENCE_GAP`).
2. Ensure `ControlSessionMachine` tracks target process incarnation and disallows re-attaching or accepting commands for a recycled PID.
3. Add unit test:
   - PID reuse/exec transition stress: stale process identity never admits old contexts and never reopens old session attribution.

---
<!-- id: issue-20260912-232404-647-enforce-process-incarnation-identity-gating-across  file: issue-20260912-232404-647-enforce-process-incarnation-identity-gating-across.md -->
