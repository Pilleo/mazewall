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
**Hypothesis:** The profiler traces syscalls using `USER_NOTIF`. If a JVM performs an `ioctl` on a terminal or specialized device file, the BPF filter might intercept it. However, the profiler might not understand the specific `ioctl` structure to extract meaningful paths or context.

`ioctl` arguments are highly dependent on the specific command. If the profiler attempts to read the argument as a string pointer (like it does for `open`), it might read random memory, causing a segmentation fault in the target process or reading garbage data.


**Needed:**
1. Ensure the BPF filter for the profiler either explicitly ignores `ioctl` (allowing it to pass through) or the `ProfilerDaemon` correctly identifies it as a generic, opaque operation without attempting deep pointer dereferencing.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt``
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
