---
title: "ArchUnit Bypass: Swallowed SyscallResult in SupervisorSessionHandler"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "unknown"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 116
---

# 🔴 [Severity: HIGH]: ArchUnit Bypass: Swallowed SyscallResult in SupervisorSessionHandler

**Context:**
`SupervisorSessionHandler` explicitly swallows `SyscallResult` inside `withTransaction` blocks by appending `; Unit` after `LinuxNative.fileSystem.close(...)` at line 651, and `LinuxNative.raw.ioctl(..., SECCOMP_IOCTL_NOTIF_SEND, ...)` at line 678. This bypasses ArchUnit validations and silently masks kernel errors if a target thread terminates during a syscall, violating the monadic result pattern described in `architectural_map.md`.


**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: `Unknown`
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
