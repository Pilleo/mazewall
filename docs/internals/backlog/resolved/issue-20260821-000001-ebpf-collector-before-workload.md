---
title: "Start the eBPF collector before invoking the workload"
severity: "HIGH"
status: "resolved"
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
related_thread: PRRT_kwDOScnnEM6a5aQ2
---

# 🔴 [Severity: P1]: Start the eBPF collector before invoking the workload

**Review (2026-08-21):** DUPLICATE of issue-20260821-113000-start-ebpf-collector-before-workload.

**Context:** When `ProfileStrategy.EBPF` is selected without a recorded log, or with an invalid log path, this invokes the potentially side-effecting workload before `drainEbpf()` calls `EbpfCollector.start()` and throws `IncompleteProfileException`. The profiling attempt therefore executes completely unobserved, and a caller retrying after the reported setup failure can duplicate external effects.

**Problem:**
- `MazewallProfiler.kt:50` - Workload is invoked before collector validation
- If `drainEbpf()` fails, workload has already executed with side effects
- Retry attempts can duplicate external effects

**Impact:**
- Side effects occur on failed profiling attempts
- Incomplete Bill of Behavior may be accepted
- Policy generation can be based on unobserved execution

**Needed:**
1. Validate and start the collector before calling `block()`. Ensure the collector is ready before any workload execution.
