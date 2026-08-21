---
title: "Preserve close-on-exec on the injected executable descriptor"
severity: "MEDIUM"
status: "open"
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
related_thread: 3819751061
---

# 🟡 [Severity: MEDIUM]: Preserve close-on-exec on the injected executable descriptor

**Context:** Zero-filling `seccomp_notif_addfd` leaves `newfd_flags=0`, so the descriptor injected into the tracee does not inherit `FD_CLOEXEC` from the supervisor's source descriptor. In the current flow the parent acknowledges without performing the advertised register rewrite and the original `execve` is continued, so this extra descriptor survives a successful exec and is exposed to the new program.

**Problem:**
- newfd_flags zero-filled
- FD_CLOEXEC not inherited
- Descriptor survives exec
- Exposed to new program

**Impact:**
- Descriptor leak to new program
- Security: unintended descriptor inheritance

**Needed:**
1. Set newfd_flags from source descriptor
2. Inherit FD_CLOEXEC flag
3. Ensure descriptor closed on exec if appropriate

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819751061
