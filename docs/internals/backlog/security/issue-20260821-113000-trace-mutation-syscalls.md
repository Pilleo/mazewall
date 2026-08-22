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
autonomy: "supervised"
open_questions: true
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
1. Add `Syscall.CREAT` (and any other missing mutation nrs you trap) to `Syscall`/`Arch` **using the add_syscall skill**.
2. Include `CREAT`, `TRUNCATE`, `FTRUNCATE` in the profiling filter’s explicit ERRNO/NOTIFY set (the profiler preset, not necessarily production `PURE_COMPUTE`).
3. Alternatively (fail closed): if those nrs are not in the installed profiling filter, `coverage.complete` must be false with a warning that mutation syscalls were not traced.

**Tests:**
- Unit: profiling filter action numbers include creat/truncate (or coverage warns they are absent).
- Compiler: observed `TRUNCATE` path is already in `fsWritePaths`; keep that test.
- Do not install USER_NOTIF on the JUnit worker without `@NeedsFreshJvm`.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789292

## ❓ Open Questions
1. **Profiler Preset vs Production Preset:** Should `CREAT`, `TRUNCATE`, `FTRUNCATE` be added directly into `PolicyPresets.PURE_COMPUTE_UNSAFE` (affecting default `block()` lists), or should we introduce a dedicated `PolicyPresets.PROFILER_SUPERVISED_MUTATIONS` preset specifically for the `USER_NOTIF` profiler session?
2. **Architecture Support (`CREAT` vs `openat`):** On `aarch64`, `creat(2)` is not implemented as a dedicated syscall (it is routed via `openat(2)` with `O_CREAT`). Should `Syscall.CREAT` be mapped as architecture-conditional (x86_64 only) with aarch64 relying exclusively on `OPENAT`/`OPENAT2` traps, or should `Syscall.CREAT` have a sentinel/noop mapping on aarch64?

