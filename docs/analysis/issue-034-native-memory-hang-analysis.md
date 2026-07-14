# High-Frequency Arena Allocation Overhead (Analysis)

## Technical Root Cause: Native Memory Exhaustion in Reactor Loops

The hang observed in `SupervisorProxyIntegrationTest` when implementing reentrant `nativeScope` optimization is caused by a fundamental mismatch between the reentrancy logic and the architecture of the daemon reactor loops.

### 1. Reentrant `nativeScope` Mechanics
The proposed optimization introduces a `ThreadLocal<Arena>` to track the active arena for a given thread.
- **Goal:** Allow nested calls to `nativeScope { ... }` to share the same underlying `Arena.ofConfined()` instance.
- **Behavior:** The outermost `nativeScope` creates the arena and sets it in the `ThreadLocal`. Nested calls detect the existing arena and reuse it. Crucially, **nested calls do not close the arena**; only the outermost scope closes it upon completion.

### 2. Reactor Loop Architecture
The `SupervisorDaemonEngine` and `ProfilerDaemonEngine` utilize long-running "reactor" loops to handle incoming syscall notifications. These loops are often structured as follows:

```kotlin
private fun handleSession(...) {
    nativeScope { arena -> // <--- OUTER SCOPE (Session Lifetime)
        // [A] Allocate long-lived structures (pollFds, notif buffers)

        while (!isGlobalShutdown()) {
            // [B] Poll for events

            // [C] Handle event
            // This call (and its sub-calls) use nativeScope internally for:
            // - Reading path strings from tracee memory
            // - Resolving absolute paths via /proc
            // - Preparing BPF instruction segments
            val action = sessionHandler.handleActiveListener(...)
        }
    } // Arena is only closed here (when session terminates)
}
```

### 3. The Failure Mechanism: Cumulative Native Leak
When reentrancy is enabled:
1. The **Outer Scope** at `[A]` starts and pins its `Arena` to the `ThreadLocal`.
2. Every iteration of the loop at `[C]` triggers nested `nativeScope` calls.
3. Because an outer scope is active, **none of the per-iteration allocations are freed**.
4. Every transient string, every temporary buffer, and every syscall argument resolution result is appended to the session-long arena.
5. In integration tests (which perform hundreds of noisy file operations like reading the JDK home), this leads to **linear native memory growth**.

### 4. Why it Hangs
The hang occurs because the JVM's native allocator eventually hits one of several resource walls:
- **Virtual Memory Saturation:** The arena grows large enough that subsequent `allocate()` calls become extremely slow due to kernel page table management overhead.
- **GC Interference:** While the *native* memory isn't managed by the GC, the objects wrapping these segments are. The high frequency of "zombie" segments pinned to the ThreadLocal arena causes massive GC pressure as the collector tries to determine what can be safely reclaimed.
- **Starvation:** The daemon thread becomes so slow that it fails to service the seccomp notification socket in a timely manner. The tracee thread (the integration test) waits for a handshake that never comes, resulting in a permanent deadlock.

### 5. Jacoco Coverage Verification Failures
During implementation, Jacoco coverage verification consistently failed. This was due to:
1. **Instruction Shift:** Refactoring reactor loops to avoid non-local returns (required by the initial `crossinline` scope) changed the bytecode structure, leading to unexpected instruction coverage drops.
2. **Environment Timeouts:** The memory exhaustion caused the test suite to take significantly longer than the 400s sandbox limit. This prevented a complete `.exec` file from being generated, resulting in artificially low coverage metrics (e.g., 0.40 vs 0.66).

## Recommendations for Future Optimization

To safely implement arena reuse, the system must distinguish between **long-lived session memory** and **transient iteration memory**.

1.  **Iterative Scoping:** The `while` loop should **NOT** be wrapped in a `nativeScope`. Instead, long-lived structures should be allocated in a private, manually managed `Arena.ofConfined()`, and the *body* of the loop should be wrapped in `nativeScope` to ensure per-iteration cleanup.
2.  **Explicit Reuse Context:** Instead of an implicit `ThreadLocal`, consider passing a `ReuseContext` or using a specialized `PerTaskArena` that allows the caller to explicitly "flush" or "checkpoint" transient allocations.
3.  **Threshold-based Refresh:** For high-frequency loops, implement a strategy that refreshes (closes and recreates) the iteration arena every N operations to bound the maximum memory footprint.
