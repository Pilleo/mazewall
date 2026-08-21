---
title: "Forward creation mode when emulating open calls"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRT_kwDOScnnEM6a5aRR
---

# 🔴 [Severity: P1]: Forward creation mode when emulating open calls

**Context:** For intercepted `open`/`openat` calls containing `O_CREAT` or `O_TMPFILE`, the original mode argument is required, but this path invokes the two-argument `NativeFileSystem.open` API and the relative branch likewise calls `openat` without a mode. The variadic libc call consequently receives no defined creation mode, so authorized workloads can create files with incorrect or overly permissive permissions.

**Problem:**
- `SupervisorSessionHandler.kt:725` - open/openat calls with O_CREAT/O_TMPFILE don't preserve mode
- NativeFileSystem.open called with 2 args (no mode)
- openat called without mode argument
- Files created with incorrect or overly permissive permissions

**Impact:**
- Security: Files may be created with incorrect permissions
- Correctness: Syscall behavior differs from expected
- Information disclosure or privilege escalation possible

**Needed:**
1. Preserve `args[2]` for `open` and `args[3]` for `openat` through the native trait. Pass the mode argument when emulating the syscall.
