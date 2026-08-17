---
title: "Pure Functional State Machines for Daemons"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonMachine.kt"
effort: "large"
autonomy: "supervised"
---

# 🟢 [Severity: HIGH]: Pure Functional State Machines for Daemons

**Context:**
`SeccompDaemonEngine` previously mutated `AtomicReference<SeccompDaemonState>` imperatively across `run()` and `triggerGlobalShutdown()`, so the lifecycle matrix could not be tested without UNIX sockets.

**Resolution:**
- `SeccompDaemonMachine.evaluate(state, event)` is a pure function returning `SeccompDaemonTransition(state, effects)`.
- Events: `Bound`, `ReadyAnnounced`, `ShutdownRequested`, `AcceptLoopFinished(serverFd)`.
- Effects: `LogListening`, `PublishReady`, `LogShutdown`, `CloseServer`, `ClearConnectionTables`, `StopConnectionWorkers`.
- The engine CAS-applies the next state and interprets effects. `AcceptLoopFinished` carries the accept-loop `serverFd` so shutdown still closes the socket after the state has become `ShuttingDown` (which holds no fd).
- Transition matrix covered by `SeccompDaemonMachineTest` with no sockets.

**Follow-up:** Profiler and supervisor engines now share `UnixListenDaemonMachine` / `UnixListenDaemonState` in `:platform`. Duplicate `ProfilerDaemonState` and `SupervisorDaemonState` remappers were removed so all three listen loops use one transition matrix.
