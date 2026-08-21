---
title: SupervisorSessionHandler catch(Throwable) swallows critical errors causing
  resource leaks
type: issue
status: resolved
priority: high
labels:
- security
- enforcer
- resource-leak
- fail-open
component: enforcer
target_modules:
- :enforcer
target_files:
- enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt
---

# Issue: `SupervisorSessionHandler.handleActiveListener` Swallows `Throwable`

## Context
In `SupervisorSessionHandler.kt`, the method `handleActiveListener` (or `processNotification`) catches `Throwable` at the top level of its event loop.

## The Bug
Catching `Throwable` inside `processNotification` swallows critical VM errors (like `OutOfMemoryError`, `StackOverflowError`, or `ThreadDeath`). If an unrecoverable structural error is encountered, logging it and attempting to send a seccomp error via `sendSeccompError(id, NativeConstants.EPERM, resp)` might fail again or leave the JVM in a corrupted state, while the event loop attempts to keep processing notifications.

Worse, the inner `try-catch` around `sendSeccompError`:
```kotlin
catch (t: Throwable) {
    logger.log(java.util.logging.Level.SEVERE, "Fatal error processing notification $id", t)
    try {
        sendSeccompError(id, NativeConstants.EPERM, resp)
    } catch (ignored: Throwable) {
        // Ignore secondary errors
    }
    return false
}
```
also catches `Throwable`, meaning if sending the error causes an `OutOfMemoryError` or similar, it's silently ignored, the tracee remains suspended in the kernel (or we return false, potentially disconnecting the session, but without terminating the loop cleanly). The tracee could be permanently hung.

## Security / Stability Impact
- **Resource Exhaustion/Deadlocks**: If structural errors are swallowed, the event loop might thrash, or the application might remain in a broken, partially functioning state.
- **Tracee Hangs**: If a `VirtualMachineError` occurs during `sendSeccompError`, it is ignored, no ACK is sent to the kernel, and the tracee thread is permanently suspended waiting for the seccomp user notification response.

## Recommendation
1. Change `catch (t: Throwable)` to `catch (e: Exception)` in `processNotification` to only catch standard exceptions (like `IOException`, `IllegalArgumentException`, etc.).
2. Let critical `Error`s (like `OutOfMemoryError`) propagate up to crash the daemon/thread cleanly, or explicitly handle them by logging and rethrowing.
3. Change the inner `catch (ignored: Throwable)` to `catch (ignored: Exception)`.
