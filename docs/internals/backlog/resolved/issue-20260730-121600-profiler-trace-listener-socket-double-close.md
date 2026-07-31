---
title: "ProfilerTraceListener socket double-close mitigation and idempotency"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
effort: "small"
autonomy: "autonomous"
github_issue: 438
---

# 🔴 [Severity: HIGH]: ProfilerTraceListener socket double-close mitigation and idempotency

**Context:**
Inside `ProfilerTraceListener.kt`, the socket file descriptor (`socketFd`) is closed in two distinct lifecycle phases:
1. In the worker thread's `finally` block:
```kotlin
            } finally {
                if (closed.compareAndSet(false, true)) {
                    try {
                        socketFd.close()
                    } catch (ignored: Exception) {}
                }
                arena.close()
                inputStream.close()
            }
```
2. In the `finally` block of `close()`:
```kotlin
        } finally {
            // Step 3: Close the socket FD only after draining.
            socketFd.close()
            workerThread = null
            collectorThread = null
        }
```

Because `FileDescriptor` is immutable, calling `socketFd.close()` does not mutate `socketFd`'s internal `isClosed` state (it only returns a closed copy that is discarded). Consequently, the immutability-based protection checks inside `FileDescriptor.close()` are ineffective when calling `.close()` multiple times on the same `socketFd` reference.

If there is a concurrency race or timing issue where the main thread's `listener.close()` executes and sets `closed` to `true`, and the worker thread subsequently evaluates its state, or if both threads concurrently try to close `socketFd` under timing variations (such as thread interrupts, slow I/O, or asynchronous JVM exceptions), the underlying `socketFd.close()` can be called twice. This leads to a native double-close vulnerability, exposing the JVM to file descriptor recycling corruption (where a recycled FD is closed out from under an unrelated component like a database socket or transaction log file).

**Needed:**
1. Wrap the socket close action in a thread-safe, idempotent close guard using an `AtomicBoolean` (e.g. `socketClosed = AtomicBoolean(false)`) inside `ProfilerTraceListener`.
2. Ensure that `socketFd.close()` is executed at most once, even if called concurrently or sequentially from multiple blocks.
3. Update `ProfilerTraceListenerTest.kt` to verify that redundant closes are handled safely and don't result in duplicate native calls to `close()`.
