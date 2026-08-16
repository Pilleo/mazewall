---
title: "ProfilerDaemonEngine active connection FD leak on session start failure"
severity: "HIGH"
status: "resolved"
priority: 8
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemonEngine.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: ProfilerDaemonEngine active connection FD leak on session start failure

**Context:**
Inside `ProfilerDaemonEngine.kt`'s connection processing logic, `handleConnection()` transitions the accepted UNIX connection statefully from `Accepted` to `FdAttached` (after receiving the seccomp notification FD), and then to `Active` (after successful handshake ACK transmission).
In the subsequent reactor loop iteration, when the state is `Active`, it delegates execution to `handleSession()`.

But if an unexpected exception, interruption, or VM `Error` (such as `OutOfMemoryError` or `ThreadDeath`) is raised after the transition to the `Active` state but BEFORE `handleSession` actually runs (or before its `finally` block takes over), a severe resource leak occurs.

Specifically, `handleConnection`'s outer `finally` block checks:
```kotlin
if (connection is io.mazewall.ffi.networking.SeccompConnection.FdAttached) {
    val lFd = connection.listenerFd
    activeListeners.remove(lFd)
    socketManager.close(lFd)
}
```
Since the `connection` object has already transitioned to `SeccompConnection.Active`, `connection is FdAttached` is false. Consequently, `handleConnection`'s `finally` block does not close the `listenerFd`. Meanwhile, since `handleSession` never completed or its `finally` block never ran, the seccomp listener FD `listenerFd` remains open and is leaked in the kernel forever.

**Needed:**
1. Refactor `ProfilerDaemonEngine.kt`'s `handleConnection()` finally block to ensure that if a received `listenerFd` is present (either in `FdAttached` or `Active` state), and the reactor session has not been successfully initiated/cleaned up, the `listenerFd` is safely closed.
2. Maintain a boolean flag `sessionStarted` or safely check the final state of the connection to prevent leaking the seccomp notification FD under transient handler-thread exceptions.
3. Add a dedicated unit test in `ProfilerDaemonTest.kt` simulating an exception thrown immediately after the transition to `Active` to verify that the listener FD is closed successfully and no leak occurs.
