---
title: "Do not grant file reads for O_PATH observations"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/BobCompiler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825912180
---

# 🟡 [Severity: MEDIUM]: Do not grant file reads for O_PATH observations

**Review (2026-08-21):** Still present. Do not confuse with io_uring unknown OPENAT (`113002-treat-iouring-open-modes`): that path stays in `opens` **and** marks coverage incomplete. This issue is POSIX `open`/`openat` with `O_PATH` in `openFlags`.

**Current tree:** `O_PATH` is `0x01000000`. `isOpenWrite` only looks at `O_WRONLY`/`O_RDWR`/`O_CREAT`/`O_TRUNC`. `O_PATH` therefore takes the `opens` branch → `toPolicy()` `allowFsRead`. Landlock `READ_FILE` is then granted for a handle that never read file contents. The compiled policy can reopen the path for real reads.

**Do not:**
- Put `O_PATH` in `fsWritePaths`.
- Treat `O_PATH` as incomplete coverage and then still add `allowFsRead` when `allowIncomplete=true` (same over-grant as the io_uring OPENAT mistake).
- Use `flags == 0` as “read”.

**Do:**
1. If `(openFlags & O_PATH) != 0`, do **not** add the path to `opens` or `fsWritePaths`. Recording the syscall in `syscalls` is fine.
2. If Landlock has no other reason to grant that path, `toPolicy` must not `allowFsRead` it.
3. Optional: coverage warning that O_PATH was observed without a content-access open.

**Tests:** `Syscall("OPENAT", paths=["/secret"], openFlags=O_PATH)` → path not in `opens` or `fsWritePaths`. `allowIncomplete` policy must not contain `allowFsRead("/secret")`. `O_RDONLY` without O_PATH still goes to `opens`.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912180
