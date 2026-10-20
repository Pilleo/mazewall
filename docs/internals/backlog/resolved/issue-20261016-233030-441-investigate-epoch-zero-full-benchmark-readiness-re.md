---
title: "Investigate epoch-zero full benchmark readiness refusal"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/internal/benchmark/readiness.go"
target_symbols:
  - "readinessFromRecords"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate epoch-zero full benchmark readiness refusal

**Context:**
Actual pinned Temurin Xint/Serial benchmark runs run-cd82fdd7f2a2 and run-a67ba18ed87e refuse full readiness within the unchanged five-second route gate. Preparation32/64 tokens retain stable epoch0 and hotspot_epoch_unavailable (31/63 terminals plus one missing_reference); increasing the existing pre-attachment allocation warmup from32 to100000 did not change the observed epoch. Baseline/events and physical owner retirement pass. Artifacts build/benchmark.00931104/full-0 and build/benchmark.795d8920/full-0 retain both original refusal and checked cleanup; no full success report is published.

**Needed:**
1. Isolate the fixture/capture epoch-zero precondition with pinned native evidence and distinguish valid conservative refusal from a missing initial epoch contract; preserve readiness/enrichment budgets, exact attribution and failure propagation.

**Resolution:** The numerical epoch was not invalid. The approved epoch contract
explicitly permits stable0; the native error's0denotes Unknown validation state.
The short preparations retain only kernel frames and no checked Java definition.
Pre-attachment allocation warmup cannot prime this later capture pipeline.
Using the existing post-attachment preparation10000 within its existing bounds
produces the first checked Java route at sequence123, still at epoch0;9877of10000
preparation records and all32measured operations are checked/correct with zero
missing/wrong operations. Both real owners and the attached client retire cleanly.
No provider, fixture, timeout, buffer, default or epoch validation was changed.
See [native diagnosis and artifacts](../../../plans/2026-10-17-hotspot-benchmark-ownership-evidence.md#readiness-diagnosis-follow-on).

---
<!-- id: issue-20261016-233030-441-investigate-epoch-zero-full-benchmark-readiness-re  file: issue-20261016-233030-441-investigate-epoch-zero-full-benchmark-readiness-re.md -->
