---
title: "Missing Return Value Check for `SECCOMP_NOTIF_RESP` ACK"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: MEDIUM]: Missing Return Value Check for `SECCOMP_NOTIF_RESP` ACK

**Context:**
**Hypothesis:** When the daemon replies to the kernel via `ioctl(SECCOMP_IOCTL_NOTIF_SEND)`, it might fail (e.g. if the tracee thread died prematurely, receiving `ENOENT`). If the daemon does not check the return value, it might leak internal state or assume the event was successfully handled, leading to desynchronization.

`ProfilerSessionHandler.kt` calls `LinuxNative.ioctl(fd, NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, respSegment.address())`. The return value is a `SyscallResult`. If `returnValue < 0`, the kernel rejected the response.


**Needed:**
1. Log a warning if the `NOTIF_SEND` ioctl returns an error.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.profiler.engine.ProfilerSessionHandler``
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
