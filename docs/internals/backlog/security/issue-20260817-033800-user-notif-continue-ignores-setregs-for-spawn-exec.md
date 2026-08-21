---
title: "USER_NOTIF CONTINUE may not honor ptrace SETREGS on posix_spawn exec"
severity: "HIGH"
status: "open"
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
---

# 🔴 [Severity: HIGH]: USER_NOTIF CONTINUE may not honor ptrace SETREGS on posix_spawn exec

**Context:**
The supervisor now opens a validated binary, injects it with `SECCOMP_IOCTL_NOTIF_ADDFD`, and asks the parent JVM to `PTRACE_SETREGS` to `execveat(fd, "", AT_EMPTY_PATH)` before `CONTINUE`. Integration `ProcessBuilder("true")` still fails (`posix_spawn` EPERM/ENOSYS). The daemon cannot ptrace the child (Yama; `PR_SET_PTRACER` is not inherited). The JVM parent can attach, but changing `pt_regs` may not change the registers seccomp will resume for `SECCOMP_USER_NOTIF_FLAG_CONTINUE`.

**Needed:**
1. Empirically confirm whether `PTRACE_SETREGS` on a task blocked in `seccomp_unotify` affects the resumed syscall (`orig_rax` / args) on this kernel.
2. If it does not, do **not** CONTINUE the original execve. Use a kernel-supported replacement (new seccomp addfd/setregs API if one exists) or complete/deny the notification and restart exec from a controlled fd without resuming the original argument set.
3. Keep fail-closed (EPERM) until that path works. Do not resume a mutable child pathname.

**Empirical notes (August 2026):**
- Changing `orig_rax` to `execveat` then CONTINUE produced `posix_spawn` **ENOSYS (38)**. The in-flight syscall number is not safely replaced that way.
- Retargeting only `rsi` to `/proc/self/fd/N` and writing that string into the child's path buffer caused the **next** exec to report `/proc/self/fd/N` (shared vfork VM). Opening that path in the supervisor is ENOENT (it is the child's fd table).
- Staying `PTRACE_ATTACH`ed until after CONTINUE **deadlocked** (`wait4` / helper retry loop of `jspawnhelper`).
- The parent JVM **can** read the child's pathname (`jspawnhelper`, then `true` once we stop clobbering the buffer). ADDFD of that file works. Resuming a safe exec still needs a kernel-supported register/arg replacement that does not hang.

**Verification:** `ProcessBuilder("true")` under the supervisor exits 0, and a concurrent mutation of the child's pathname buffer cannot change the executed file.
