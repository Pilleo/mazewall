---
title: "Reject observations that cannot map to a syscall"
severity: "MEDIUM"
status: "open"
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
related_thread: 3825912173
---

# 🟡 [Severity: MEDIUM]: Reject observations that cannot map to a syscall

**Context:** When strace or eBPF successfully records a syscall absent from the limited `Syscall` enum—such as `epoll_wait`, `readv`, or `recvmsg`—this silently discards it while coverage can still report `complete=true`. Compiling that result against an allow-list base then omits an operation the workload actually required, while the Bill of Behavior inaccurately claims completeness.

**Problem:**
- Syscalls not in Syscall enum are silently discarded
- Coverage can report complete=true
- Policy omits operations workload required
- Bill of Behavior inaccurately claims completeness

**Impact:**
- Policy may not cover all observed operations
- Bill of Behavior incomplete

**Needed:**
1. Reject or mark incomplete when syscall not in enum
2. Expand Syscall enum to cover common syscalls
3. Don't silently discard unmappable observations

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912173
