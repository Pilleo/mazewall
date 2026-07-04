---
title: "🟡 [DEFERRED — Medium]: JVM Invariant Syscall Floor is Incomplete"
severity: "MEDIUM"
status: "open"
---

# 🟡 [DEFERRED — Medium]: JVM Invariant Syscall Floor is Incomplete

**Context:** `BpfFilter.getJvmCriticalNrs()` contains 7 hardcoded syscalls established empirically on one JVM (Temurin G1GC x86-64). ZGC, Shenandoah, Loom, and GraalVM require additional syscalls (`userfaultfd`, `ioctl(UFFDIO_*)`, `rt_sigprocmask`, `memfd_create`, Loom epoll/eventfd calls). Profiling-based approaches are fundamentally incomplete (only capture exercised paths, miss GC-pressure-triggered and JIT-background paths). Source analysis is the correct approach but requires JVM internals expertise and cannot easily cover GraalVM separately.
**Needed:** See `docs/internals/jvm_syscall_floor_research.md` for full option analysis. Recommended path: Option E (source analysis + stress harness validation). Short-term: manually add confirmed-missing entries (`rt_sigprocmask`, non-EXEC `mmap`/`mprotect`) to `getJvmCriticalNrs()`.
