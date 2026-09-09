---
title: Refactor Profiler Daemon to use Coroutines (Structured Concurrency)
severity: ENHANCEMENT
status: open
priority: low
dependencies: []
target_files:
- profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt
target_modules:
- :profiler
component: profiler
effort: large
paperclip_issue_id: "55f5b748-c448-44a8-a64b-69099bce4cf5"
paperclip_identifier: "MAZ-1252"
---

# 🔵 [Severity: ENHANCEMENT]: Refactor Profiler Daemon to use Coroutines (Structured Concurrency)

**Target:** `io.mazewall.profiler.engine.ProfilerDaemonEngine` and `ProfilerSessionHandler`
**Context:** The current profiler daemon uses a "thread-per-connection" model and manual thread management for handling tracee sessions. This is heavyweight and makes graceful shutdown/cancellation complex.
**Needed:** Transition the daemon to a coroutine-based architecture.
1. Use `supervisorScope` and `launch` for managing connection handlers and session loops.
2. Replace synchronous `transport.poll` loops with non-blocking equivalents (e.g., using a coroutine-friendly wrapper around `epoll` or `io_uring`).
3. This improves the daemon's scalability and makes its lifecycle management more robust and idiomatic.
