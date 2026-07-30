---
title: "Prevent file descriptor and local socket leaks in Supervisor's async accept worker thread under failure conditions"
severity: "HIGH"
status: "resolved"
priority: 8
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "medium"
autonomy: "autonomous"
github_issue: 406
---

# 🔴 [Severity: HIGH]: Prevent file descriptor and local socket leaks in Supervisor's async accept worker thread under failure conditions

**Context:**
Inside `SupervisorSessionHandler.kt`'s `handleAcceptAsync`, a new daemon thread is spawned asynchronously to accept a connection on a duplicated socket descriptor from the tracee process. It executes:
1. `pidfdOpen` to open a file descriptor for the tracee's thread group ID.
2. `pidfdGetFd` to duplicate the tracee's server file descriptor into the supervisor's process context (creating `dupFd`).
3. `accept4` on the duplicated FD to accept the client connection, creating `clientFd`.
4. Injects `clientFd` into the tracee using seccomp `SECCOMP_IOCTL_NOTIF_ADDFD`.

**The Bug:**
The closing of `pidfd` is placed in a separate try-finally block of `accept4`'s duplication rather than wrapping the entire worker thread's resource lifecycle. Specifically:
- If `pidfdGetFd` fails (e.g., throwing or returning a negative/error result) or if an unexpected exception occurs between `pidfdOpen` and `pidfdGetFd`, the opened `pidfd` is never closed and leaks permanently.
- Similarly, if thread interruption or an exception occurs during peer address copying or seccomp injection, the socket or duplicated descriptors (like `clientFd` or `dupFd`) might fail to close, presenting a local resource-leak vulnerability that can exhaust the daemon's file descriptor table under high concurrency or repeated connection failure sequences.

**Needed:**
1. Audit the entire lifecycle of local file descriptors inside `handleAcceptAsync`.
2. Implement a strict, nested try-finally block structure or wrap descriptors in auto-closeable value classes to guarantee that `pidfd`, `dupFd`, and `clientFd` are unconditionally and safely closed upon any failure, exception, or thread interruption.
3. Write a targeted unit test simulating a `pidfdGetFd` or `accept4` failure to verify zero FD leakage.
