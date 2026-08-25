---
title: "Reject observations that cannot map to a syscall"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/BobCompiler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825912173
---

# 🟡 [Severity: MEDIUM]: Reject observations that cannot map to a syscall

**Review (2026-08-21):** Still present. Adding a few names to `isFileSystemMutation` does **not** fix this.

**Current tree:** `applySyscall` does `Syscall.valueOf(name).getOrNull()?.let { syscalls.add(it) }` and otherwise continues. Names absent from `Syscall` (`CREAT`, `recvmsg`, `epoll_wait`, …) never enter `BillOfBehavior.syscalls`. Coverage can still be `complete=true` because it keys off path-bearing sets, not “every observation mapped”. An allow-list compile then omits a syscall the workload used.

**Do not:**
- Silently skip unmapped names (status quo).
- Expand `Syscall` for the entire Linux table in this issue (that is `add_syscall` work, separate PRs).
- Mark only path-bearing failures and leave unmapped non-path syscalls as complete.

**Do:**
1. If a `ProfileObservation.Syscall` name does not map to `Syscall`, treat the profile as incomplete: warning + `complete=false`.
2. Optionally collect unmapped names on the Bob/coverage object for the operator.
3. Do not add the name to `syscalls` via a fake enum.

**Tests:** Observation `name="RECVMSG"` (or another name not in `Syscall`) + otherwise clean coverage inputs → `complete=false` and `toPolicy()` throws without `allowIncomplete`. Mapped names such as `OPENAT` still compile.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912173
