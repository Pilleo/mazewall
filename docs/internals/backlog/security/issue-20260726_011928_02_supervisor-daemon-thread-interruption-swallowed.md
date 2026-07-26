---
title: SupervisorSessionHandler loop swallows ThreadInterruptedException breaking
  graceful shutdown
type: issue
status: open
priority: 8
labels:
- security
- enforcer
- graceful-shutdown
- deadlock
component: enforcer
target_modules:
- :enforcer
target_files:
- enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt
---

# Issue: `SupervisorSessionHandler` Loop Swallows Thread Interruption

## Context
When reading JVM responses or reading notifications in the supervisor daemon loop, `EINTR` (interrupted system call) is often returned when the process is signaled, or when a shutdown is requested.

## The Bug
In `SupervisorSessionHandler.kt`, `readAndHandleJvmResponse`, there is a progressive backoff loop:
```kotlin
if (eintrCount > 1) {
    if (eintrCount > 3) {
        try {
            Thread.sleep(1)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
    } else {
        Thread.yield()
    }
}
```
If `InterruptedException` is caught, `Thread.currentThread().interrupt()` is called, and it `break`s from the `readAndHandleJvmResponse` loop.

However, after `readAndHandleJvmResponse` returns, the outer loop in `SupervisorDaemonEngine` (or wherever `handleActiveListener` is called from) might not check `Thread.currentThread().isInterrupted` promptly, or it might be swallowed by another catch block.

Specifically, in `SupervisorSessionHandler.processNotification`:
```kotlin
} catch (t: Throwable) {
```
If `processNotification` calls something that throws an `InterruptedException` (or an exception caused by thread interruption), the interrupt is caught, wrapped, and the loop continues.

Also, `Thread.interrupted()` resets the interrupt status. If any mock tests or code paths call `Thread.interrupted()` without re-asserting it, the shutdown signal is lost.

## Security / Stability Impact
- **Denial of Service / Unkillable Processes**: The daemon cannot be shut down cleanly if thread interruption flags are swallowed. This breaks container orchestrators (like Kubernetes) that rely on `SIGTERM` triggering a clean shutdown via thread interrupts before sending `SIGKILL`.

## Recommendation
1. Explicitly check `if (Thread.currentThread().isInterrupted) return false` at the top of `processNotification` and `handleActiveListener`.
2. Do not catch `Throwable` at the top level, which can accidentally catch and swallow custom unchecked exceptions representing interruptions or VM failures.
