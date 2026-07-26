---
title: "`SyscallPathResolver` correctly resolves `SYMLINKAT` path parameters"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: `SyscallPathResolver` correctly resolves `SYMLINKAT` path parameters

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.profiler.engine.SyscallPathResolver` (migrated from `ProfilerDaemon.getPathArgs`)
**Original Bug:** `SYMLINKAT` was grouped with `RENAMEAT`/`LINKAT` in a four-argument (olddirfd, oldpath, newdirfd, newpath) branch. The Linux `symlinkat(target, newdirfd, linkpath)` signature puts `newdirfd` at `args[1]` — not a string pointer — so reading it as a char* caused `EFAULT` failures, meaning zero paths were ever resolved for `symlinkat` calls.
**Fix:**
1. `SyscallPathResolver` now has a dedicated `"SYMLINKAT"` branch:
   ```kotlin
   "SYMLINKAT" ->
       listOfNotNull(
           tryRead(tid, args[0]),          // target — raw string pointer (no dirfd)
           tryRead(tid, args[2], args[1]), // linkpath relative to newdirfd
       )
   ```
2. `args[1]` is correctly treated as the `newdirfd` integer, not a string pointer.
3. `args[3]` (unused register) is never accessed.
**Verification:** `SyscallPathResolverTest` (June 2026) includes a regression-guard test that asserts only `args[0]` and `args[2]` are ever passed to `readStringFromProcess`, with `args[1]` used solely as the dirfd. Additional tests cover absolute linkpath, AT_FDCWD, and contrast against the correct RENAMEAT/LINKAT four-argument layout.
