---
title: "Unhandled Signal Interruptions (`EINTR`) during Supervisor Initialization"
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
github_issue: 165
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during Supervisor Initialization

**Context:**
**Hypothesis:** Sending the socket file descriptors over `SCM_RIGHTS` can be interrupted by signals.

`SupervisorSocketUtils.sendDescriptor` relies on `sendmsg`, which can fail with `EINTR`. If `EINTR` occurs while `SupervisorSeccompNotifInstaller` is trying to pass the seccomp listener FD to the supervisor daemon, the daemon will not receive the FD, but the JVM might proceed or throw an error, leading to an inconsistent state or unmonitored tracee.


**Needed:**
1. Wrap `sendmsg` inside `SupervisorSocketUtils.sendDescriptor` in a `while (errno == EINTR)` retry loop.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSeccompNotifInstaller.kt``
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
