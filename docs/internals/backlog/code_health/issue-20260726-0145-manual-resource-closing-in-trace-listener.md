---
title: Manual resource closing in ProfilerTraceListener violates scoping patterns
type: issue
status: open
priority: medium
labels: ["code-health", "profiler", "resource-leak", "refactoring"]
component: profiler
target_modules: [":profiler"]
target_files: ["io.mazewall.profiler.internal.ProfilerTraceListener.kt"]
---

# Issue: Manual Resource Closing Anti-Pattern

## Context
The `mazewall` architecture strongly encourages the use of Kotlin's `use { }` blocks for `AutoCloseable` resources to guarantee deterministic cleanup and prevent resource leaks during unexpected exceptions (as tracked in memory).

## The Bug
In `ProfilerTraceListener.kt`, the `sendCommand` function manually creates a `NativeArena` via `Arena.ofConfined().use { arena -> ... }` but relies on a broader, manual `.close()` override pattern in `ProfilerTraceListener` itself to manage the lifecycle of the `socketFd`. The listener manually handles interrupts, thread joins, and final socket closure inside nested `try-finally` blocks. This manual orchestration is prone to errors, thread interruption swallows, and violates the established Kotlin scoping practices in the repository.

## Recommendation
Refactor `ProfilerTraceListener` and its invocations to fully utilize idiomatic Kotlin resource scoping constructs (like `use {}`) to ensure `socketFd` and associated threads are managed and cleaned up reliably. Extract the shutdown logic into a cleaner, testable state machine or encapsulated closer.
