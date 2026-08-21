---
title: "Preserve requested close-on-exec state on injected FDs"
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
related_thread: 3819861583
---

# 🟡 [Severity: MEDIUM]: Preserve requested close-on-exec state on injected FDs

**Review (2026-08-21):** Still present for **open/accept inject**. Exec inject is **out of scope** (already `O_CLOEXEC` by design; see `issue-20260821-113002-preserve-cloexec-injected-exec`).

**Current tree:** After supervisor `open`/`accept`, ADDFD always `setNewfdFlags(O_CLOEXEC)` (open path ~line 683, accept ~line 992). `newfd_flags` is the **injected** descriptor’s `FD_CLOEXEC`, not a copy of the supervisor source fd. For `open` without `O_CLOEXEC` / `accept` without `SOCK_CLOEXEC`, the POSIX contract is that the new fd **survives** a later exec. Forcing CLOEXEC closes it across exec and breaks allowed programs.

**Do not:**
- Clear CLOEXEC on **execve fd-emulation** injects. Those must stay CLOEXEC so the O_PATH fd does not leak into the new image (`113002-preserve-cloexec-injected-exec`).
- Copy `FD_CLOEXEC` from the supervisor’s source fd via `F_GETFD`. The source is a supervisor-local file; the tracee’s contract comes from **syscall flags** (`O_CLOEXEC` / `SOCK_CLOEXEC` / `accept4` flags).
- Set `newfd_flags=0` for every inject as a global “fix”.

**Do:**
1. Open-family: `newfd_flags = (flags & O_CLOEXEC) != 0 ? O_CLOEXEC : 0`.
2. accept/accept4: `newfd_flags = (flags & SOCK_CLOEXEC) != 0 ? O_CLOEXEC : 0`.
3. Exec rewrite inject: keep unconditional `O_CLOEXEC`.

**Tests:** Mock ADDFD for `open(O_RDONLY)` → `getNewfdFlags()==0`. `open(O_RDONLY|O_CLOEXEC)` → `O_CLOEXEC`. `accept4(..., SOCK_CLOEXEC)` → `O_CLOEXEC`. Exec inject test must still assert CLOEXEC.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861583
