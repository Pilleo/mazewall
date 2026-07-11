---
title: "Unhandled Signal Interruptions (`EINTR`) during Supervisor IPC socket communication"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during Supervisor IPC socket communication

**Context:**
**Hypothesis:** The JVM validation loop reads from and writes to the supervisor socket. System calls over the socket can be interrupted by signals (`EINTR`).

In `SupervisorSessionHandler.kt`, `LinuxNative.poll`, `LinuxNative.memory.write`, etc. are used. The `readAndHandleJvmResponse` and `sendSeccompError`/`sendSeccompContinue` functions do not properly loop on `EINTR` when calling `write` or `ioctl`. If a signal is received during the `ioctl(SECCOMP_IOCTL_NOTIF_SEND)` call, it may fail with `EINTR`, leaving the tracee suspended forever as the notification is never correctly answered.


**Needed:**
1. Wrap the `ioctl` calls for `SECCOMP_IOCTL_NOTIF_SEND` and `SECCOMP_IOCTL_NOTIF_ADDFD` in an explicit retry loop that checks `if (errno == EINTR) continue`.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
