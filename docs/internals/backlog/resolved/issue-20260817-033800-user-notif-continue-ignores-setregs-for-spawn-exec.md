---
title: "USER_NOTIF CONTINUE may not honor ptrace SETREGS on posix_spawn exec"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/TraceeExecveRegisters.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "large"
autonomy: "supervised"
open_questions: false
---

# 🔴 [Severity: HIGH]: USER_NOTIF CONTINUE may not honor ptrace SETREGS on posix_spawn exec

**Context:**
The supervisor opens a validated binary, injects it with `SECCOMP_IOCTL_NOTIF_ADDFD`, and asks the parent JVM to `PTRACE_SETREGS` to `execveat(fd, "", AT_EMPTY_PATH)` before `CONTINUE`. Integration `ProcessBuilder("true")` fails (`posix_spawn` EPERM/ENOSYS). The daemon cannot ptrace the child (Yama; `PR_SET_PTRACER` is not inherited). The JVM parent can attach, but changing `pt_regs` does not change the registers seccomp resumes for `SECCOMP_USER_NOTIF_FLAG_CONTINUE`.

**Needed:**
1. Empirically confirm whether `PTRACE_SETREGS` on a task blocked in `seccomp_unotify` affects the resumed syscall (`orig_rax` / args) on this kernel.
2. If it does not, do **not** CONTINUE the original execve. Use a kernel-supported replacement or complete/deny the notification (fail-closed EPERM) and restart exec from a controlled fd without resuming the original argument set.
3. Keep fail-closed (EPERM) until that path works. Do not resume a mutable child pathname.

**Empirical notes (August 2026):**
- Changing `orig_rax` to `execveat` then CONTINUE produced `posix_spawn` **ENOSYS (38)**. The in-flight syscall number is not safely replaced that way.
- Retargeting only `rsi` to `/proc/self/fd/N` and writing that string into the child's path buffer caused the **next** exec to report `/proc/self/fd/N` (shared vfork VM). Opening that path in the supervisor is ENOENT (it is the child's fd table).
- Staying `PTRACE_ATTACH`ed until after CONTINUE **deadlocked** (`wait4` / helper retry loop of `jspawnhelper`).
- The parent JVM **can** read the child's pathname (`jspawnhelper`, then `true` once we stop clobbering the buffer). ADDFD of that file works. Resuming a safe exec still needs a kernel-supported register/arg replacement that does not hang.

**Architectural Decision:**
Dynamic register rewriting via `PTRACE_SETREGS` before `SECCOMP_USER_NOTIF_FLAG_CONTINUE` is fundamentally rejected by the Linux kernel with `ENOSYS` because the kernel dispatches the original trapped syscall. Execution containment must therefore rely on:
1. **Tier 1 Process-Wide Baseline:** `NO_EXEC` (blocking `execve`/`execveat` entirely via seccomp).
2. **Landlock LSM Execution Rules:** Landlock ABI v5 `LANDLOCK_ACCESS_FS_EXECUTE` to enforce binary path execution boundaries at the kernel VFS layer without TOCTOU or ptrace interception.
3. **Fail-Closed:** Supervised execve notifications must respond with `error = -EPERM` when unapproved binaries are executed.

**Verification:** `ProcessBuilder("true")` under the supervisor fails closed (EPERM), and a concurrent mutation of the child's pathname buffer cannot change the executed file.


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


**Resolution (2026-08-23) — decision IMPLEMENTED and hardened:** Verified shipped behavior:
the parent validation listener REFUSES all register-rewrite requests
(`SupervisorInstaller.completeParentExecRewrite` -> `sendExecRewriteAck(false)`), and the daemon
therefore fails closed with EPERM for EVERY supervised execve/execveat on every architecture —
matching this issue's decision exactly (`ProcessSpawnStacktraceTest` passes because denial +
parent-stack attribution work, not because exec succeeds).

Hardening added in this pass:
1. REMOVED the latent TOCTOU fallback: when `TraceeReadOnlyNul.find()` finds no read-only NUL,
   the daemon previously fell back to the tracee-WRITABLE original pathname pointer before
   requesting the rewrite. It now denies EPERM and releases the injected fd instead.
2. `handleSecureExecve` KDoc aligned with reality (rewrite requested, parent refuses, deny).
3. Legacy unit test asserting the unsafe fallback+CONTINUE was rewritten to pin the fail-closed
   semantics (ADDFD happens; NOTIF_SEND carries an error reply, never USER_NOTIF_FLAG_CONTINUE;
   no process_vm_writev pathname mutation).
Scaffolding (request/TraceeReadOnlyNul/completeParentExecRewrite) intentionally retained for a
future kernel-supported replacement.
