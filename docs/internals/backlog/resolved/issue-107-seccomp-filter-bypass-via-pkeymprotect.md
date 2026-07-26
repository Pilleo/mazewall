---
title: "🟢 [RESOLVED]: Seccomp Filter Bypass via `pkey_mprotect`"
severity: "RESOLVED"
status: "resolved"
---

# 🟢 [RESOLVED]: Seccomp Filter Bypass via `pkey_mprotect`

**Target:** `io.mazewall.BpfFilter`, `io.mazewall.core.Syscall`, `io.mazewall.seccomp.MmapProtectionTest`
**Failure Hypothesis:** The BPF filter correctly intercepts `mprotect` and `mmap` calls to prevent `PROT_EXEC` via argument inspection (checking `args[2]`). However, it misses modern Linux memory protection variants, specifically `pkey_mprotect` (`SYS_pkey_mprotect` / 329 on AMD64). Since this syscall is not explicitly hooked for argument inspection and may be allowed under loose policies or fallback behavior, an attacker who can call `pkey_mprotect` can mark memory as executable (`PROT_EXEC`), completely bypassing the Seccomp `NO_EXEC` protections designed to stop dynamic shellcode generation.
**Context & Proof:** `pkey_mprotect` takes the same `prot` parameter as `mprotect` but also takes a `pkey`. The current `BpfFilter.kt` only restricts `arch.mmap` and `arch.mprotect`. In `Syscall.kt`, there is no representation of `pkey_mprotect`. Thus, if `pkey_mprotect` is not explicitly blocked or handled via argument inspection like `mprotect`, it will fall back to the default action. Under `Policy.NO_EXEC`, `pkey_mprotect` isn't explicitly blocked, so it would fall to `ACT_ALLOW`, allowing unrestricted `PROT_EXEC` usage. This has been proven via `bypass_pkey.c` where `mprotect` with `PROT_EXEC` is blocked but `pkey_mprotect` with `PROT_EXEC` succeeds in bypassing.
**Vulnerability Chain Potential:** Very high. If an attacker achieves arbitrary code execution (or memory corruption) they can just use `pkey_mprotect` instead of `mprotect` to bypass JIT / dynamic shellcode protections in the sandbox.
**Fix:** Added `PKEY_MPROTECT` to `Syscall`, mapped its number per architecture, and in `BpfFilter.buildFromActions` added it to the same argument inspection block that currently restricts `PROT_EXEC` in `mprotect` and `mmap`. Added tests to `MmapProtectionTest.kt` to guarantee blocking.
**Failure Hypothesis:** A thread pool processing multiple tasks with a whitelist policy (where `defaultAction != ACT_ALLOW`) will unconditionally attach a new, redundant Seccomp BPF filter on every task execution, eventually crashing the thread when the filter limit is reached.
