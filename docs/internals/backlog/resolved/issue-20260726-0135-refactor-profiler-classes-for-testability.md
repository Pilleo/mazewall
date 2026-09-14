---
title: Refactor Profiler core classes for testability and test them
severity: MEDIUM
status: resolved
priority: high
dependencies: []
component: profiler
target_modules:
- :profiler
target_files:
- profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt
- profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemonEngine.kt
- profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt
- profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerDaemonTest.kt
- profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerDaemonStateTest.kt
effort: large
autonomy: supervised
paperclip_issue_id: 8335dcb9-1cce-4001-894d-f3587e2d554f
paperclip_identifier: MAZ-760
---

# 🔴 [Severity: MEDIUM]: Refactor Profiler core classes for testability and test them

**Context:** The `:profiler` module's coverage is currently at ~73%. The remaining untested code resides in highly complex, OS-integrated classes such as `IterativeProfiler`, `Profiler`, `ProfilerDaemon`, and `ProfilerSessionHandler`. These classes tightly couple JVM logic with Linux processes (via `ProcessBuilder` or direct NativeEngine invocations) and daemon orchestration, making them extremely difficult to test cleanly with standard JVM unit tests without relying on heavy mocking frameworks or flaky environment setups (e.g. timeout issues, unexpected JVM interrupts). Because the repository discourages mock-heavy testing (as per KDocs and `AGENTS.md`), these classes need structural refactoring to improve their inherent testability.

**Needed:**
1. Extract socket acceptance, shutdown, and client-session scheduling from `ProfilerDaemon`/`ProfilerDaemonEngine` behind deterministic seams; keep production ownership and fail-closed cleanup unchanged.
2. Separate `ProfilerSessionHandler` protocol transitions from direct FFM operations so ACK-loop behavior can be tested without native fixtures.
3. Add focused tests for idle clients, shutdown races, transport failures, and session retirement; do not duplicate the completed Tier E attribution seams.
4. Raise profiler coverage above 80% only through behaviorally meaningful tests, not broad exclusions or mock-heavy replacements.

**Progress (2026-08-23 and subsequent Tier E refactoring):** `IterativeProfiler` and Tier E
attribution components already have testable state/transport seams. The remaining scope is the
production profiler daemon and session handler only; iterative-profiler extraction is complete
and no longer belongs in this issue.

## Resolution (2026-09-14)

`ProfilerDaemonEngine` delegates acceptance, shutdown, and session scheduling through injectable transport seams. `ProfilerSessionHandler` receives protocol, responder, memory, and preparation dependencies directly; focused daemon tests cover stalled and idle work, malformed input, ACK transport failure, deferred shutdown, and session retirement.
