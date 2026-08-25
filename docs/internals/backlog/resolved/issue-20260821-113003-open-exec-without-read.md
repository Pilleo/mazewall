---
title: "Open executable targets without requiring read permission"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819751067
---

# 🟡 [Severity: MEDIUM]: Open executable targets without requiring read permission

**Review (2026-08-21):** ALREADY FIXED: exec inject opens with O_PATH|O_CLOEXEC, not O_RDONLY.

**Context:** Opening the validated target with only `O_CLOEXEC` means an `O_RDONLY` open, so an execute-only binary with valid execute permission but no read permission is rejected with `EACCES` before the original `execve` can proceed. Linux execution does not require the caller to have read permission.

**Problem:**
- Target opened with O_RDONLY
- Execute-only binary requires read permission
- Rejected with EACCES
- Linux doesn't require read for execution

**Impact:**
- Execution blocked for execute-only binaries
- Functionality: can't execute binaries without read permission

**Needed:**
1. Obtain O_PATH | O_CLOEXEC handle for execveat(AT_EMPTY_PATH)
2. Don't require read permission for execution
3. Use execution-capable handle

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819751067
