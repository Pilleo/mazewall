---
title: JvmFloorWorkload OS Thread leak due to infinite retry loop on exception
type: issue
status: open
priority: medium
labels: ["security", "enforcer", "resource-exhaustion"]
component: enforcer
target_modules: [":enforcer"]
target_files: ["io.mazewall.enforcer.JvmFloorWorkload.kt"]
---

# Issue: `JvmFloorWorkload` Background OS Thread Leak

## Context
`JvmFloorWorkload` executes various background operations (Loom carrier threads, GC threads) to trigger JVM internal syscalls like `mprotect`, `futex`, etc.

## The Bug
The operations are submitted to an executor or run in a background loop that catches `Exception` and potentially loops infinitely, ignoring interrupted status or failing to propagate it properly to the daemon. While standard executors in production code are banned, `JvmFloorWorkload` is exempt.

If it spins quickly ignoring interruptions during a shutdown, it leaks the background thread and increases CPU utilization to 100%.

## Recommendation
Audit `JvmFloorWorkload` to ensure it properly checks `Thread.currentThread().isInterrupted` and exits cleanly instead of infinitely catching and spinning on exceptions during teardown.
