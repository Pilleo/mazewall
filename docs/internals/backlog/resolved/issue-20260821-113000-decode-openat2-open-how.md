---
title: "Decode openat2's open_how before injecting the descriptor"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "medium"
autonomy: "supervised"
related_pr: 512
related_thread: PRRT_kwDOScnnEM6a5aRK
---

# 🔴 [Severity: HIGH]: Decode openat2's open_how before injecting the descriptor

**Review (2026-08-21):** Still present. Duplicate `issue-20260821-000004-openat2-open-how` is closed. Creation-mode for **open/openat** is a separate issue (`113000-forward-creation-mode`); do not conflate them.

**Current tree:** `openFileInSupervisor` does `val flags = if (nr == arch.open) args[1].toInt() else args[2].toInt()` then `fileSystem.open`/`openat`. For `openat2`, `args[2]` is a **userspace pointer** to `struct open_how { flags, mode, resolve }`, not an int flags word. Using the pointer bits as flags drops `RESOLVE_BENEATH` / `RESOLVE_NO_SYMLINKS` / `RESOLVE_NO_XDEV` and invents garbage flags.

**Do not:**
- Cast the pointer to `Int` and pass it to `open(2)`.
- Emulate `openat2` with `openat` while ignoring `resolve`. That can open a path the tracee was not allowed to resolve.
- “Fix” by always denying `openat2` **without** a test that the deny is what production does, unless the chosen design is fail-closed deny until a real `openat2` emulator exists. If you deny, say so in KDoc and tests.

**Do (pick one, document it, test it):**
1. **Preferred:** `process_vm_readv` the `open_how`, validate `resolve`, emulate with `openat2` (need a native downcall that accepts how), inject that fd.
2. **Fail-closed alternative:** if how cannot be read or `resolve != 0` cannot be honored, `sendSeccompError(EPERM)` / `EOPNOTSUPP`. Never CONTINUE the original `openat2` after validating a different open.

**Tests:** Mock tracee memory with `open_how.flags=O_RDONLY, resolve=RESOLVE_BENEATH`. Assert the supervisor does **not** call `open`/`openat` with the pointer value as flags. Either `openat2` is used with those fields, or the syscall is denied.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861564
