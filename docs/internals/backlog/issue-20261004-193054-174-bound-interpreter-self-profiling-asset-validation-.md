---
title: "Bound interpreter self-profiling asset validation and dense readout cost"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot"
  - "profiler/hotspot-agentless/Makefile"
  - "profiler/hotspot-agentless/scripts/test-release-footprint.py"
target_symbols:
  - "HotSpotAssets"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bound interpreter self-profiling asset validation and dense readout cost

**Context:**
Real pinned -Xint current-JVM acceptance measured 34-82 second asset-admission cost and rejected a 600-operation 1ms window under the unchanged 2-second stop bound; snapshots preserved only 153-298 received records before abort

**Needed:**
1. Retain rejected fixture measurements and profile validation/JSON mapping cost before changing supported workload bounds or native budgets

**Diagnosis2026-10-05:** Interpreter checksum median70.273s/95,912,323bytes;
helper95,730,281bytes. JFR94.18% of3938stacks contain JDK SHA2. Synthetic600replay
median3.304s (1.399s mapping/1.905s remainder), final3.870ms. Actual dense run
rejects268received/267retained/1pending with no pending resource ownership; all7
guarded cases run/pass. See [evidence](../../plans/2026-10-05-hotspot-interpreter-performance-evidence.md).
Keep open: diagnosis complete, cost reduction unproved. Next candidate is release
helper footprint reduction with actual integrity/compatibility/admission comparison.
No budgets or runtime guarantees change based on these measurements.

**Footprint2026-10-05:** Two pinned builds reproduce a68,231,330byte stripped
helper (28.7254% smaller). All7candidate native/public cases execute/pass;
matched checksum median70.944s ->44.966s, but live admission still69.8–78.3s and
dense run rejects283received/182retained/101pending with complete resource cleanup.
Source/license candidate distribution is retained. See
[footprint evidence](../../plans/2026-10-05-hotspot-release-footprint-evidence.md).
Keep open for remaining admission/dense-readout costs; do not infer a latency bound.

---
<!-- id: issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-  file: issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md -->
