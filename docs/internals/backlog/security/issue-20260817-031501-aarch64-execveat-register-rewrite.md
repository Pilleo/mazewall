---
title: "execveat AT_EMPTY_PATH register rewrite on aarch64"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":platform"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/NativeConstants.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: execveat AT_EMPTY_PATH register rewrite on aarch64

**Context:**
Secure exec emulation on x86_64 evaluated `PTRACE_GETREGS`/`SETREGS`. On aarch64 the supervisor fail-closes every allowed execve. That is correct (no pathname CONTINUE) but blocks supervised `ProcessBuilder` on arm64 hosts.

**Needed:**
1. Maintain fail-closed (`-EPERM`) behavior for supervised `execve` on both x86_64 and aarch64.
2. Align with resolution of `issue-20260817-033800`: do not attempt unsound `PTRACE_SETREGSET` rewrites before `CONTINUE` (which the Linux kernel rejects with `ENOSYS`).
3. Leverage Landlock ABI v5 `LANDLOCK_ACCESS_FS_EXECUTE` and Tier 1 `NO_EXEC` baseline for execution containment on aarch64.

**Architectural Decision:**
Deferred/Superseded by `issue-20260817-033800`. Dynamic register rewriting prior to seccomp `CONTINUE` is rejected by the Linux kernel on all architectures. Safe execution containment relies on Landlock LSM and Tier 1 process-wide baseline filters.


