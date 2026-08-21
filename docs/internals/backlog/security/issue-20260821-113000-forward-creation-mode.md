---
title: "Forward creation mode when emulating open calls"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/NativeEngine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "small"
autonomy: "supervised"
related_pr: 512
related_thread: PRRT_kwDOScnnEM6a5aRR
---

# 🔴 [Severity: HIGH]: Forward creation mode when emulating open calls

**Review (2026-08-21):** Still present. Duplicate `issue-20260821-000005-open-mode-forwarding` is closed. `openat2` how.mode belongs in `113000-decode-openat2-open-how`, not here.

**Current tree:** `NativeFileSystem.open(path, flags)` and `openat(dirfd, path, flags)` have **no mode**. `openFileInSupervisor` therefore cannot pass `args[2]` (open) / `args[3]` (openat) when `O_CREAT` or `O_TMPFILE` is set. The kernel then uses an undefined mode for the variadic syscall.

**Do not:**
- Hard-code `0666` or `0600` as a “safe default”. That is still not the tracee’s mode.
- Continue the original syscall after opening without mode (TOCTOU + wrong file).
- Change only the Kotlin wrapper and leave the FFM downcall at 2 arguments.

**Do:**
1. Extend the native trait (and FFM downcall) with `mode: Int` used when `flags` include `O_CREAT` or `O_TMPFILE`.
2. Pass `args[2]` for `open`, `args[3]` for `openat`.
3. If mode cannot be read, deny the notification.

**Tests:** Intercepted `open(path, O_CREAT|O_WRONLY, 0640)` — mock native open must receive mode `0640`. Missing mode → error response, not CONTINUE.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861568
