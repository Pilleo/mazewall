---
title: "Silent failure of Profiler path resolution under Yama `ptrace_scope` > 1 leads to catastrophic Landlock enforcement failures"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Silent failure of Profiler path resolution under Yama `ptrace_scope` > 1 leads to catastrophic Landlock enforcement failures

**Status:** RESOLVED (July 2026)
**Target:** `io.mazewall.profiler.engine.ProfilerDaemon`
**Context:** A system administrator configures Linux with Yama `kernel.yama.ptrace_scope = 2` (admin-only attach). When the `mazewall` Profiler daemon attempts to read path arguments using `process_vm_readv` on the JVM threads, the kernel denies the read with `EPERM` (1). The daemon caught this `EPERM` and gracefully returned `null` for the read string, which yielded an empty list of paths and compiled into an empty SBoB that caused a complete filesystem lockdown.
**Fix:** The profiler now returns a `"<YAMA_ERROR_UNKNOWN_PATH>"` sentinel when `process_vm_readv` fails with `EPERM`, and `BobCompiler` throws an `IllegalStateException` to prevent compiling a corrupted empty profile, warning the developer to configure ptrace tracing appropriately.
