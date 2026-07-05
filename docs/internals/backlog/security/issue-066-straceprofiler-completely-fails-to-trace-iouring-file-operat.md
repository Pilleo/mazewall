---
title: "StraceProfiler completely fails to trace `io_uring` file operations natively"
severity: "CRITICAL"
status: "open"
priority: 5
dependencies: []
component: "profiler"
effort: "medium"
---

# 🔴 [Severity: CRITICAL]: StraceProfiler completely fails to trace `io_uring` file operations natively

**Target:** `io.mazewall.profiler.strace.StraceProfiler`, `docs/internals/profiler_design.md`
**Context:** The `profiler_design.md` document claims that Tier P (`StraceProfiler`) natively captures paths and async execution of `io_uring` (stating "Tier P (Root) | Paths and async captured natively"). This is fundamentally impossible under the current implementation and kernel constraints.
1. `StraceProfiler` executes `strace -f -e trace=file,network`. The `trace=file` class traces syscalls that take a string path argument (e.g., `openat`, `stat`). It *does not* include `io_uring_enter`.
2. Even if `io_uring_enter` were traced, the file paths exist entirely in the shared memory Submission Queue Entries (SQEs), not as standard string arguments to a syscall.
3. When the kernel processes these SQEs (often via `io-wq` kernel threads), the VFS operations occur entirely within kernel space. No user-space syscall boundary is crossed, so `ptrace` (which powers `strace`) is completely blind to them.
4. Consequently, if a workload relies on `io_uring` for file access, `StraceProfiler` will silently miss all accessed paths, producing broken policies. The claim in the documentation that `strace` captures `io_uring` paths natively is objectively false.
**Needed:**
1. Update `docs/internals/profiler_design.md` to remove the false claim that Tier P traces async `io_uring` natively. Emphasize that Tier A (Iterative Profiler) is the *only* profiler that can correctly learn `io_uring` Landlock paths (by failing and retrying) unless the application's `io_uring` is disabled during tracing (the Hybrid approach).
2. For Tier P, developers must either run with the Hybrid approach (disabling `io_uring` during profiling to force fallback to standard POSIX I/O) or rely on Iterative profiling.
