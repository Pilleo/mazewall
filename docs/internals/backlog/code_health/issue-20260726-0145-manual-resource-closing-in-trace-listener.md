---
title: "Manual Resource Closing in Trace Listener thread"
severity: "MEDIUM"
status: "open"
priority: 5
dependencies: []
target_files: ["profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"]
target_modules: [":profiler"]
component: "profiler"
effort: "small"
autonomy: "supervised"
---

# 🟡 [Severity: MEDIUM]: Manual Resource Closing in Trace Listener thread

**Context:**
In `ProfilerTraceListener.kt`, the listener background thread manually calls `arena.close()` and `inputStream.close()` inside a `finally` block:
```kotlin
                if (closed.compareAndSet(false, true)) {
                    try {
                        socketFd.close()
                    } catch (ignored: Exception) {}
                }
                arena.close()
                inputStream.close()
```

**Problem:**
While technically correct because it's wrapped in a `try...finally` block inside the `Thread` runnable, manually managing lifecycle methods makes the code brittle and less idiomatic. Kotlin's `use` block is the standard mechanism to ensure `AutoCloseable` resources like `NativeArena` and `NativeSocketInputStream` are deterministically cleaned up, even in complex failure branches.

**Needed:**
Refactor the background thread's run implementation to leverage standard `.use { ... }` scoped blocks for `arena` and `inputStream`, moving them out of manual initialization and finalization.
