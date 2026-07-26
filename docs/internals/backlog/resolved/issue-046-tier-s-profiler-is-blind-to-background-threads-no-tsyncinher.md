---
title: "Tier S Profiler is blind to background threads (No TSYNC/Inheritance)"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED] [Severity: HIGH]: Tier S Profiler is blind to background threads (No TSYNC/Inheritance)

**Target:** `io.mazewall.profiler.Profiler.kt`, `io.mazewall.profiler.engine.ProfilerInstaller.kt`
**Context:** Seccomp filters and `USER_NOTIF` file descriptors are per-thread by default. The current Tier S `Profiler.profile { ... }` only installs the filter on the calling thread. Background JVM threads (GC, JIT, ForkJoinPool) completely bypass the profiler, leading to an incomplete "JVM Floor" baseline.
**Needed:** Implement process-wide tracing support in Tier S. Two potential paths:
1. **`SECCOMP_FILTER_FLAG_TSYNC`:** Synchronize the filter to all existing threads in the thread group at installation time.
2. **`SECCOMP_FILTER_FLAG_NEW_LISTENER` + Clone Tracking:** Ensure new child threads automatically inherit the seccomp filter and notify the same supervisor daemon.
This is critical for generating a production-grade JVM Syscall Floor that accounts for background management tasks.
