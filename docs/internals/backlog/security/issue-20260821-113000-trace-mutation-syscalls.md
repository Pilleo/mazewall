---
title: "Trace mutation syscalls before certifying USER_NOTIF coverage"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
  - ":enforcer"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
  - "enforcer/src/main/kotlin/io/mazewall/PolicyPresets.kt"
  - "platform/src/main/kotlin/io/mazewall/core/Syscall.kt"
effort: "medium"
autonomy: "autonomous"
open_questions: false
related_pr: 512
related_thread: 3823789292
---

# 🔴 [Severity: HIGH]: Trace mutation syscalls before certifying USER_NOTIF coverage

**Review (2026-08-21):** Still present. BobCompiler already classifies `CREAT`/`TRUNCATE` **if observed**. The gap is that USER_NOTIF never observes them.

**Current tree:**
- `Profiler.profile` installs `PolicyPresets.PURE_COMPUTE_UNSAFE`.
- That preset `block()`s OPEN\*, RENAME\*, LINK\*, UNLINK\*, MKDIR\*, CHMOD\*, network, exec. It does **not** block `TRUNCATE`. **`CREAT` is not in the `Syscall` enum**, so it cannot be blocked or converted to USER_NOTIF.
- `BpfFilter` profiling mode turns **explicit ERRNO actions** into `USER_NOTIF`. Default `ACT_ALLOW` syscalls are not trapped.
- Native `creat()` / `truncate()` therefore run with no observation. `ProfilingCoverage.infer` can still set `complete=true` if other path-bearing events look resolved.

**Do not:**
- Add `CREAT` to `isFileSystemMutation` and close this issue (already there; does not trap the syscall).
- Mark coverage complete when the profiled filter never installed a trap for known mutation nrs.
- Convert **default ALLOW** to USER_NOTIF for every syscall (that poisons JVM coordination; see enforcer AGENTS.md).

**Do:**
1. Add `Syscall.CREAT` (and any other missing mutation nrs you trap) to `Syscall`/`Arch` **using the add_syscall skill** (`x86_64=85`, `aarch64=Arch.UNSUPPORTED`).
2. Include `CREAT`, `TRUNCATE`, `FTRUNCATE` in `PolicyPresets.PURE_COMPUTE_UNSAFE` (and profiler traps).
3. If those nrs are not in the installed profiling filter, `coverage.complete` must be false with a warning that mutation syscalls were not traced.

**Design Decisions:**
1. **Profiler Preset & Production Preset:** `TRUNCATE`, `FTRUNCATE`, and `CREAT` belong in `PolicyPresets.PURE_COMPUTE_UNSAFE` as pure compute lockdown blocks all filesystem mutations.
2. **Architecture Mapping:** `Syscall.CREAT` is assigned `85` on `x86_64` and `Arch.UNSUPPORTED` (`-1L`) on `aarch64` (where the kernel only provides `openat` with `O_CREAT`). `BpfFilter` automatically filters out `Arch.UNSUPPORTED` numbers when generating architecture-specific BPF jump tables.

**Tests:**
- Unit: profiling filter action numbers include creat/truncate (or coverage warns they are absent).
- Compiler: observed `TRUNCATE` path is already in `fsWritePaths`; keep that test.
- Do not install USER_NOTIF on the JUnit worker without `@NeedsFreshJvm`.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789292


