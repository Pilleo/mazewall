---
title: "Profiler Public API Target"
scope: "profiler"
critical_syscalls: ["seccomp", "ioctl", "process_vm_readv"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingResult.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt"
keywords: ["public-api", "profiling", "coverage", "session-lifecycle"]
---

# Profiler Public API Target

## Current Strengths

`Profiler.profile { workload() }` is concise, preserves the workload result, and connects observations to `BillOfBehavior`, policy generation, DSL and JSON output.

## Confirmed Gaps

### Session Ownership

The profiler uses global daemon/listener state, exposes `shutdown()` with ambiguous semantics, and exposes mutable recent logs on the executor wrapper. Iterative profiling also uses mutable global registrations. Concurrent or repeated test suites need isolated sessions with deterministic cleanup.

### Strategy Selection

USER_NOTIF, iterative Landlock and descendant `strace` profiling have separate entry points and different limitations. A facade should accept `ProfileStrategy.AUTO`, select only a compatible strategy, and record the selected strategy and reason. It must never silently claim equivalent coverage between strategies.

### Confidence and Coverage

A dynamic trace cannot produce an “exact” policy. `ProfilingResult` should include a structured coverage report containing:

- thread or process scope;
- child-process and background-thread coverage;
- io_uring visibility;
- filesystem argument resolution quality;
- stack-attribution status;
- environment tuple and runtime versions;
- warnings, dropped events and incomplete shutdown/drain state.

### Behavioral Domain Model

`BillOfBehavior` should expose typed paths, syscall observations, executions and network observations rather than raw strings or engine-level `TraceEvent` keys. Its serialized schema and README examples must agree; documentation currently advertises `networkEndpoints`, which the data class does not expose.

### Proposed Shape

```kotlin
MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.AUTO)).use { session ->
    val result = session.profile { workload() }
    result.value
    result.behavior
    result.coverage
    val policy = result.toPolicy()
}
```

Executor profiling should return a closeable `ProfilingExecutorSession` that owns its executor or explicitly adopts a dedicated one. Snapshots should be immutable:

```kotlin
session.snapshot(): ProfilingResult<Unit>
```

## Failure Semantics

Profiler setup, event loss, daemon failure and incomplete drain must be typed outcomes. A partial result may be returned only when explicitly requested and must carry `complete = false`; it must never be emitted as an enforcement-ready policy without an explicit override.
