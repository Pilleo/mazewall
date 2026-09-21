---
title: "Diagnose intermittent Tier E pre-admission observation gaps under sustained load"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEKernelSmoke.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt"
target_symbols:
  - "TierEKernelSmoke"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose intermittent Tier E pre-admission observation gaps under sustained load

**Context:**
Rootful 1,000 operations/s sustained samples intermittently missed contiguous declared operations
before exact admission with zero ring loss and no marker-loss branch. A same-run, counter-only raw
`sys_enter` denominator reproduced the missing descriptor groups and found no `openat2`
substitution. The benchmark container exposed CPUs `0,2`, while every Tier E perf-event program was
attached only to CPU `0`; pinning the target leader did not pin all JVM carrier threads. The engine
now attaches marker, syscall, exec, and benchmark-audit programs on every allowed online CPU.

**Needed:**
1. Retain a clean, multi-fork rootful sustained confirmation of the all-allowed-CPU attachment fix,
   including the benchmark-only raw denominator as diagnostic provenance. Do not promote a capacity
   ceiling from source-dirty smoke evidence.

---
<!-- id: issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa  file: issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md -->
