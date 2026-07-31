---
title: "Eliminate Transient FFM Allocations and Memory Leaks in Nested Loops"
severity: "MEDIUM"
status: "open"
priority: 8
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemonEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt"
effort: "medium"
autonomy: "autonomous"
github_issue: 448
---

# 🔶 [Severity: MEDIUM]: Eliminate Transient FFM Allocations and Memory Leaks in Nested Loops

**Context:**
The `ProfilerDaemonEngine` session loop runs continuously, polling for seccomp events. Within high-throughput loops, some helper objects are allocated inside nested loops or passed transiently without explicit arena scopes, leading to memory leaks or JVM GC pressure due to off-heap native memory overhead.

Specifically, in `handleSession`, we have:
```kotlin
                        val pollRes = ioOps.raw.poll(pollFds, 2L, POLL_TIMEOUT_MS)
                        ...
                        NativeArena.ofConfined().use { iterationArena ->
                            val action = with(iterationArena) {
                                sessionHandler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd)
                            }
                            if (action !is LoopAction.Continue) break
                        }
```
While `iterationArena` is closed deterministically at the end of each iteration, `ackBuf` (size 1 byte) and `socketPollFd` (size POLLFD) are allocated in the parent session arena.
Furthermore, within `ProfilerSessionHandler.processNotification`, we allocate short-lived heap objects (like `SyscallEvent` wrappers) and transient string arrays. Under high syscall volumes, these heap-based allocations can cause garbage collection pauses that block tracee threads.

**Needed:**
1. Pre-allocate all static buffers (such as `ackBuf`, `socketPollFd`, and diagnostic metadata buffers) once per session, storing them in `ProfilerSessionHandler` instead of allocating them or managing them inside high-frequency execution loops.
2. Ensure that any transient strings or arrays generated during path normalization are stored in thread-local reusable byte arrays or pooled memory buffers to avoid garbage collection pressure on the host JVM.
3. Add a micro-benchmark test verifying that under a load of 100,000 syscall notifications, memory allocation is constant and zero off-heap memory leaks occur.

**Verification/Regression Tests:**
- Run `./gradlew :profiler:test` to confirm the correctness of session loops under high load.
