---
title: "Missing Return Value Check for `SECCOMP_NOTIF_RESP` ACK"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 185
---

# 🔴 [Severity: MEDIUM]: Missing Return Value Check for `SECCOMP_NOTIF_RESP` ACK

**Context:**
**Hypothesis:** After processing a notification, the profiler sends a response back to the kernel via `ioctl(SECCOMP_IOCTL_NOTIF_SEND)`. If this `ioctl` fails (e.g., because the target thread was killed or interrupted in the meantime), the profiler might not handle the error gracefully, potentially leaking state or crashing the daemon loop.

The `USER_NOTIF` documentation states that the target thread can be interrupted or killed before the response is sent. The `ioctl` will return `ENOENT` in this case.


**Needed:**
1. Explicitly catch and ignore `ENOENT` errors during the `NOTIF_SEND` `ioctl`, as they represent expected, normal race conditions during thread termination.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt``
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
