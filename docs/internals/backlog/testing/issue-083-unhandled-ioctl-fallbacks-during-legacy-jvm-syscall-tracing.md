---
title: "Unhandled `IOCTL` fallbacks during legacy JVM syscall tracing"
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

# 🔴 [Severity: MEDIUM]: Unhandled `IOCTL` fallbacks during legacy JVM syscall tracing

**Context:**
**Hypothesis:** When tracing `IOCTL`, older kernels may pass unexpected data structures in the argument block due to architectural differences or internal kernel fallbacks. If the `ProfilerDaemon` attempts to read these structs from memory unconditionally, it may hit unmapped pages or receive structurally malformed data, leading to incomplete traces or Daemon crashes on specific kernel versions.

The `ProfilerDaemon` intercepts syscalls via `USER_NOTIF`. For complex syscalls with pointer arguments (like `ioctl`), it reads the argument memory using `process_vm_readv`. However, standard `ioctl` arguments are highly polymorphic and depend heavily on the device and request code. Attempting to parse them generically without strict bounds checking or request-code verification can cause `process_vm_readv` to fail or read garbage.


**Needed:**
1. Implement robust request-code filtering and structural bounds checking before attempting to read `ioctl` argument payloads in the Profiler Daemon.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.profiler.engine.ProfilerDaemon``
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
