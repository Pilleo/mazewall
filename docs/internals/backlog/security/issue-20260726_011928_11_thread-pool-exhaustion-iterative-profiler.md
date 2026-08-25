---
title: IterativeProfiler can exhaust thread pools on recursive containerization limits
type: issue
status: open
priority: medium
labels:
- security
- enforcer
- resource-exhaustion
component: enforcer
target_modules:
- :enforcer
target_files:
- profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt
paperclip_issue_id: db7d4adf-7e2a-4eb5-9126-38c6b3542be7
---

# Issue: `IterativeProfiler` Thread Exhaustion

## Context
`IterativeProfiler` repeatedly reruns tasks until they no longer fail with `SecurityException` due to seccomp/Landlock denials.

## The Bug
When `IterativeProfiler.profile` wraps a block and runs it on a standard JVM thread pool, the block will fail, triggering an iterative loop. If the target operation is a highly concurrent operation spawning thousands of threads, the profiling retry loop could exponentially multiply the number of threads. This can cause Thread Pool Exhaustion (`OutOfMemoryError: unable to create new native thread`).

## Recommendation
Implement a backoff mechanism or enforce a strict max iteration limit for `IterativeProfiler` to prevent it from spinning indefinitely or spawning too many recursive tasks.
