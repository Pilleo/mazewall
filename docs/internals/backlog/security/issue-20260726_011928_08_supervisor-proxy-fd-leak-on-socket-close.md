---
title: SupervisorDaemonEngine fd leak when handleActiveListener throws
type: issue
status: open
priority: 5
labels:
- security
- enforcer
- fd-leak
component: enforcer
target_modules:
- :enforcer
target_files:
- enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngine.kt
---

# Issue: `SupervisorDaemonEngine` fails to close listener FDs on exception

## Context
The `SupervisorDaemonEngine` manages seccomp NOTIF file descriptors sent over UNIX sockets via `SCM_RIGHTS`.

## The Bug
When `engine.raw.recvmsg` succeeds and returns a file descriptor list `fds`, those file descriptors are now open in the current process.
If `handleActiveListener` throws an exception, or if `readAndHandleJvmResponse` crashes, the file descriptor for the seccomp listener is never explicitly closed.

In `SupervisorDaemonEngine.kt` (or wherever `handleNewConnection` or `handleActiveListener` is), the `fds` array contains kernel file descriptors. If the JVM loop terminates or a thread is interrupted, the OS keeps those FDs open until the daemon process exits. If the daemon process is long-lived and processes multiple connections or restarts internal loops, it will leak FDs until it hits `ulimit -n` and crashes.

## Recommendation
Implement a strictly scoped `try-finally` block around the extraction and usage of FDs received from `recvmsg`. Ensure that `FileDescriptor` abstractions (if used here) are properly closed (`socketManager.close()`) even when `processNotification` or `handleActiveListener` throws.
