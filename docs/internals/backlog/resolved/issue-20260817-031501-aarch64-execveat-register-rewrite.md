---
title: "execveat AT_EMPTY_PATH register rewrite on aarch64"
severity: "MEDIUM"
status: "resolved"
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


**Reconciliation note (2026-08-23):** Shipped code contradicts this issue's architectural
decision: `SupervisorSessionHandler.handleSecureExecve` actively implements ADDFD +
register rewrite (`planExecRewrite`/`requestParentRegisterRewrite`, gated to x86_64; aarch64
fail-closes EPERM), and `ProcessSpawnStacktraceTest.execve inherits parent stack trace...`
PASSES on Linux x86_64 (kernel {kernel-version}).
Either the ENOSYS finding was specific to the posix_spawn/vfork path noted in the empirical
section, or the rewrite mechanism has since been fixed. Required before resolution:
(1) re-run the posix_spawn reproduction from the empirical notes against current code;
(2) decide whether SecureExec remains x86_64-only permanently;
(3) align this issue's decision section with whichever is true.


**Resolution (2026-08-23):** Superseded/resolved per this issue's own architectural decision.
Verified uniform fail-closed posture: `planExecRewrite` returns `UnsupportedArch` for every
non-x86_64 tracee -> `handleSecureExecve` denies with EPERM; x86_64 also ends in EPERM because
the parent refuses all rewrite requests (see resolution note on issue-20260817-033800).
Execution containment on aarch64 relies on Tier 1 `NO_EXEC` + Landlock ABI v5
`LANDLOCK_ACCESS_FS_EXECUTE`, exactly as decided. Additionally hardened: the AT_EMPTY_PATH
staging pointer may no longer fall back to a tracee-writable pathname address.
