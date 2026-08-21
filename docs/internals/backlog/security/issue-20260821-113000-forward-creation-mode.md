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
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: 3823789305
---

# 🔴 [Severity: HIGH]: Forward creation mode when emulating open calls

**Context:** For intercepted `open`/`openat` calls containing `O_CREAT` or `O_TMPFILE`, the original mode argument is required, but this path invokes the two-argument `NativeFileSystem.open` API and the relative branch likewise calls `openat` without a mode. The variadic libc call consequently receives no defined creation mode, so authorized workloads can create files with wrong permissions or fail unexpectedly.

**Problem:**
- O_CREAT/O_TMPFILE require mode argument
- Two-argument NativeFileSystem.open called
- Mode not forwarded
- Files created with wrong permissions or call fails

**Impact:**
- Security: files created with wrong permissions
- Functionality: calls may fail

**Needed:**
1. Pass mode argument to NativeFileSystem.open
2. Use three-argument open/openat variants
3. Ensure mode is correctly forwarded

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789305
