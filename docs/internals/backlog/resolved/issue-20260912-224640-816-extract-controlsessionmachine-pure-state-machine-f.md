---
title: "Extract ControlSessionMachine pure state machine from TierEKotlinDaemon"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ControlSessionMachine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/ControlSessionMachineTest.kt"
target_symbols: 
  - "ControlSessionMachine"
  - "TierEDaemonService"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Extract ControlSessionMachine pure state machine from TierEKotlinDaemon

**Context:**
TierEKotlinDaemon couples socket IO, mutable engine reference, PID parsing, and state transitions, hindering fast golden-file protocol tests.

**Needed:**
1. Define ControlSessionState and ControlSessionMachine with evaluate(state, event)

**Resolution evidence (2026-09-13):**
Extracted pure `ControlSessionMachine` and `ControlSessionState` / `ControlSessionEvent` / `ControlSessionEffect` in `ControlSessionMachine.kt`. Refactored `TierEKotlinDaemon.kt` to delegate execution through `TierEDaemonService` and `ControlSessionMachine.evaluate(state, event)`.
Added `ControlSessionMachineTest` verifying all state transitions (Idle, Attaching, Attached, Terminated) and effects without sockets.
Both `./gradlew :profiler:test` and `./gradlew detektWarningRatchet` pass with zero added warnings and removed 3 previous warnings from `TierEKotlinDaemon.kt`.

---
<!-- id: issue-20260912-224640-816-extract-controlsessionmachine-pure-state-machine-f  file: issue-20260912-224640-816-extract-controlsessionmachine-pure-state-machine-f.md -->

