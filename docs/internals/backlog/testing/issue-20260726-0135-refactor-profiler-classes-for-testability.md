---
title: Refactor Profiler core classes for testability and test them
severity: MEDIUM
status: open
priority: high
dependencies: []
component: profiler
target_modules:
- :profiler
target_files:
- profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt
- profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt
- profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt
- profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt
effort: large
autonomy: supervised
---

# 🔴 [Severity: MEDIUM]: Refactor Profiler core classes for testability and test them

**Context:** The `:profiler` module's coverage is currently at ~73%. The remaining untested code resides in highly complex, OS-integrated classes such as `IterativeProfiler`, `Profiler`, `ProfilerDaemon`, and `ProfilerSessionHandler`. These classes tightly couple JVM logic with Linux processes (via `ProcessBuilder` or direct NativeEngine invocations) and daemon orchestration, making them extremely difficult to test cleanly with standard JVM unit tests without relying on heavy mocking frameworks or flaky environment setups (e.g. timeout issues, unexpected JVM interrupts). Because the repository discourages mock-heavy testing (as per KDocs and `AGENTS.md`), these classes need structural refactoring to improve their inherent testability.

**Needed:**
1. Apply dependency injection (DI) patterns to extract process spawning, I/O logic, and global static dependencies (like `ProfilerDaemonManager.getInstance()`) from `Profiler` and `IterativeProfiler`.
2. Extract the socket handling and main loop from `ProfilerDaemon` into a testable interface that can run independently of `System.in`/`exitProcess`.
3. Separate the Seccomp protocol handling logic in `ProfilerSessionHandler` from the direct FFM interactions, allowing deterministic verification of the ACK loop protocol.
4. Implement the unit and integration tests necessary to increase the `:profiler` module coverage strictly above 80%, using parameterized tests and testing shared logic without mocking where possible.

**Progress (2026-08-23):** Partially delivered:
- `IterativeProfiler` extracted into pure state-machine + pluggable `IterativeTaskExecutor`
  (DI seam for process spawning/installation); unit-tested without mocks for the state layer.
- DiagnosticTriageRunner external-process capture is timeout-bounded and injectable-free.
Remaining: `ProfilerDaemon` socket/main-loop extraction and
`SupervisorDaemonManager.getInstance()` decoupling inside MazewallProfiler paths.
