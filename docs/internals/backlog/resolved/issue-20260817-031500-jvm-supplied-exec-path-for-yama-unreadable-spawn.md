---
title: "Send JVM-known exec path in supervisor allow response"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/Layouts.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorValidationChannel.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/PendingSpawnRegistry.kt"
effort: "medium"
autonomy: "supervised"
---

# ✅ [RESOLVED]: Send JVM-known exec path in supervisor allow response

**Status:** RESOLVED (August 2026)
**Fix:** `SupervisorResponse` carries an optional path. The in-JVM listener is the posix_spawn parent so it can `process_vm_readv` the child when the daemon cannot (Yama). Relative names are resolved via `PATH`. `handleSecureExecve` opens that path when the daemon-extracted path is missing. No pathname CONTINUE.

# 🔴 [Severity: HIGH]: Send JVM-known exec path in supervisor allow response

**Context:**
Allowed `execve` now opens the validated path in the supervisor and injects it as `execveat(AT_EMPTY_PATH)`. posix_spawn / `ProcessBuilder` execs run in a vfork/clone child. `PR_SET_PTRACER` is not inherited, so `process_vm_readv` of the child's pathname often fails (Yama). The supervisor then has no path to open and fail-closes with EPERM. The JVM parent already knows the command (`ProcessBuilder` / `ProcessImpl`).

**Needed:**
1. Extend `SupervisorResponse` with an optional NUL-terminated path (or a sidecar write) filled by the in-JVM validation listener when it allows EXECVE/EXECVEAT.
2. Record the intended command on the parent thread in `PendingSpawnRegistry` at spawn time if the listener cannot recover it from the stack alone.
3. `handleSecureExecve` must open that JVM-supplied path when tracee memory is unreadable — never CONTINUE on the child's pathname pointer.
4. Tests: `ProcessBuilder("true")` under the supervisor succeeds when the parent recorded `true`/`/usr/bin/true`, and still EPERM if the JVM sent no path.

**Verification:** Integration test that `ProcessBuilder("true").start()` completes and that a mutated child pathname cannot change the opened file.
