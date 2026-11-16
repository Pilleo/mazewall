---
title: "Propagate all-target workload worker failures before completion markers"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - "profiler-native"
target_files:
  - "profiler-native/tests/TierEAllTargetWorkload.java"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEAllTargetWorkloadTest.kt"
  - "profiler/build.gradle.kts"
target_symbols:
  - "main"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Propagate all-target workload worker failures before completion markers

**Context:**
While interpreting G4's retained 18-of-20 loadTemplate failure, source inspection showed worker exceptions only reach uncaught handlers while main joins and writes attributed-complete/completed with exit0. This is an oracle limitation, not an established cause of G4's gap.

**Needed:**
1. Add a host subprocess regression that preserves the exact20+20 normal workload and proves a worker exception reaches main as failure before phase completion; retain both worker errors and physical joins.
2. Keep normal cold platform-thread behavior and the later phase unchanged; no retries, warm-up or reduced counts. A regression may supply a failing fixture input outside the frozen diagnostic arm. Declare any additional test/build path before implementation.

## Source evidence and limits

[Original TierEAllTargetWorkload.java](../../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/reliability-001/before/profiler-native/tests/TierEAllTargetWorkload.java):26–35 rethrows worker exceptions only on that worker. Lines36–41 join both workers and write phase/terminal completion without inspecting their failures. Thread.join establishes termination, not successful completion. This weakens the markers/exit status as an independent operation-count oracle.

The G4 sample retained target wait0, attributed-complete and completed, while observing18 loadTemplate openat records. No child exception is established in that sample; do not call this the missing-open root cause. G4's immutable evidence and exact20+20 G5 workload remain untouched. This intake is a separate later fixture correction, not authority to change either experiment now.

## Resolution — 2026-11-05

Both platform workers now capture uncaught failures before start; main physically joins both,
then throws one aggregate retaining both causes before either completion marker. Exact20+20
cold operations and the later20 remain. The actual-source host subprocess regression first
reproduced old exit0 after both failures, then passed the failure and success cases.

Evidence: [terminal reliability handoff](../../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/reliability-kotlin-001/handoff.md),
`reliability-001/worker-red.xml`, `worker-green.xml`, independent scoped review and the successor
full build exit0. The successor build skipped20 rootful container tests; this resolution closes
the host-oracle defect, not G4 causality or new native/workload qualification.

---
<!-- id: issue-20261105-070311-459-propagate-all-target-workload-worker-failures-befo  file: issue-20261105-070311-459-propagate-all-target-workload-worker-failures-befo.md -->
