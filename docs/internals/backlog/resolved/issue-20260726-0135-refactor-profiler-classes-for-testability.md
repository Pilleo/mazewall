---
title: Refactor Profiler core classes for testability and test them
severity: MEDIUM
status: open
priority: 8
dependencies: []
component: profiler
target_modules:
- :profiler
target_files:[profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt,profiler/src/test/kotlin/io/mazewall/profiler/iterative/IterativeProfilerTest.kt,profiler/src/test/kotlin/io/mazewall/profiler/iterative/IterativeProfilerStateMachineTest.kt,profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeTaskExecutor.kt,profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt,profiler/src/test/kotlin/io/mazewall/profiler/ProfilerTest.kt,profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt,profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerDaemonTest.kt,profiler/src/main/kotlin/io/mazewall/profiler/engine/DaemonRunner.kt,profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemonEngine.kt,profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerInstallerInterface.kt,profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt,profiler/src/main/kotlin/io/mazewall/profiler/engine/SeccompNotificationParser.kt]
effort: large
autonomy: supervised
github_issue: 334
---

# 🔴 [Severity: MEDIUM]: Refactor Profiler core classes for testability and test them

**Context:** The `:profiler` module's coverage is currently at ~73%. The remaining untested code resides in highly complex, OS-integrated classes such as `IterativeProfiler`, `Profiler`, `ProfilerDaemon`, and `ProfilerSessionHandler`. These classes tightly couple JVM logic with Linux processes (via `ProcessBuilder` or direct NativeEngine invocations) and daemon orchestration, making them extremely difficult to test cleanly with standard JVM unit tests without relying on heavy mocking frameworks or flaky environment setups (e.g. timeout issues, unexpected JVM interrupts). Because the repository discourages mock-heavy testing (as per KDocs and `AGENTS.md`), these classes need structural refactoring to improve their inherent testability.

**Needed:**
1. Apply dependency injection (DI) patterns to extract process spawning, I/O logic, and global static dependencies (like `ProfilerDaemonManager.getInstance()`) from `Profiler` and `IterativeProfiler`.
2. Extract the socket handling and main loop from `ProfilerDaemon` into a testable interface that can run independently of `System.in`/`exitProcess`.
3. Separate the Seccomp protocol handling logic in `ProfilerSessionHandler` from the direct FFM interactions, allowing deterministic verification of the ACK loop protocol.
4. Implement the unit and integration tests necessary to increase the `:profiler` module coverage strictly above 80%, using parameterized tests and testing shared logic without mocking where possible.
