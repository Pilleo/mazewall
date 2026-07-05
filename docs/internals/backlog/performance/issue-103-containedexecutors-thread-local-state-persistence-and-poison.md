---
title: "🟢 [WONTFIX]: `ContainedExecutors` Thread-Local State Persistence and Poisoning"
severity: "MEDIUM"
status: "open"
priority: 6
dependencies: []
component: "enforcer"
effort: "medium"
---

# 🟢 [WONTFIX]: `ContainedExecutors` Thread-Local State Persistence and Poisoning

**Target:** `io.mazewall.enforcer.ContainedExecutors.kt` and `ContainerStateRegistry.kt`
**Context:** `ContainedExecutorWrapper` calls `applyContainment()` on every task execution, but it never clears the tracking `ThreadLocals`. Because worker threads are reused in a pool, any subsequent task scheduled on the same OS thread will inherit the `mazewall` state of the previous task, even if it's supposed to be uncontained or have a different policy. The original proposal was to implement a `try-finally` cleanup to clear all registers in `ContainerStateRegistry` when a contained task completes to prevent ClassLoader memory leaks on application redeploys.
**Resolution (WONTFIX):** Seccomp filters and Landlock domains are permanent and immutable for the lifetime of an OS thread. They cannot be removed or reverted. If we clear the `ThreadLocal` JVM tracking state when a task completes:
1. The JVM loses track of the permanent OS restrictions.
2. The next task on the same thread will evaluate an "empty" JVM state and redundantly re-apply the identical Landlock domain and Seccomp filters.
3. This completely breaks deduplication. If a thread processes 16 tasks, it hits the Landlock `E2BIG` stacked domain limit and crashes. If it processes 32 tasks, it hits the Seccomp stacked filter limit and crashes.
4. If a task with a *different* policy runs, the OS will silently enforce the intersection of both policies, leading to obfuscated `EPERM` crashes. Keeping the `ThreadLocal` intact allows the JVM to fail-fast with an `IllegalStateException` ("Cannot expand Landlock filesystem permissions on an already restricted thread"), properly warning the user that they are violating the immutable OS sandbox semantics.

**The Correct Solution:** Developers MUST NOT share thread pools between differently-sandboxed tasks. Restricted tasks must run on a dedicated `ExecutorService` that is shut down (`executor.shutdown()`) when the application/container stops. Shutting down the executor kills the OS threads, inherently cleaning up both the ClassLoader references and the permanent OS sandboxes without any memory leaks.

**The Correct Solution:** Developers MUST NOT share thread pools between differently-sandboxed tasks. Restricted tasks must run on a dedicated `ExecutorService` that is shut down (`executor.shutdown()`) when the application/container stops. Shutting down the executor kills the OS threads, inherently cleaning up both the ClassLoader references and the permanent OS sandboxes without any memory leaks.

## Resolved & WONTFIX Historical Backlog
