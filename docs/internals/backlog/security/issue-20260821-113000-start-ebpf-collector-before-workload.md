---
title: "Start the eBPF collector before invoking the workload"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/MazewallProfiler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3823789271
---

# 🔴 [Severity: HIGH]: Start the eBPF collector before invoking the workload

**Context:** When `ProfileStrategy.EBPF` is selected without a recorded log, or with an invalid log path, this invokes the potentially side-effecting workload before `drainEbpf()` calls `EbpfCollector.start()` and throws `IncompleteProfileException`. The profiling attempt therefore executes completely unobserved, and a caller retrying after the reported setup failure can duplicate external effects.

**Problem:**
- Workload invoked before collector starts
- draineEbpf() starts collector after workload
- IncompleteProfileException thrown but workload already ran
- External effects duplicated on retry

**Impact:**
- Side effects occur without observation
- Retry can duplicate external effects

**Needed:**
1. Validate and start collector before invoking workload
2. Only invoke workload after collector is ready
3. Ensure profiling is fail-closed

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789271
