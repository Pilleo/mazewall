---
title: "Expose agentless collection health from existing reader counters"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/internal/capture/collector.go"
  - "experiments/hotspot-agentless/internal/capture/health.go"
  - "experiments/hotspot-agentless/internal/capture/health_test.go"
  - "experiments/hotspot-agentless/internal/model/record.go"
  - "experiments/hotspot-agentless/patches/**"
  - "experiments/hotspot-agentless/scripts/prepare-gadget.py"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/tests/integration/structured_test.go"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/results/README.md"
  - "docs/internals/backlog/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Expose agentless collection health from existing reader counters

**Context:**
Report existing Gadget and OTel reader loss counters with explicit observed/partial semantics; verify native LRU eviction and retain existing capacity/pruning/buffer defaults.

**Needed:**
1. Add narrow reader-counter accessors and health aggregation tests; keep whole-session loss completeness unknown unless a producer/reader barrier is actually established.

**Implemented locally; review pending:**
- Gadget cumulative, isolated, non-destructive forwarded-loss accessor under the
  existing datasource lock; real upstream hook was previously a no-op.
- Owned OTel trace-reader observations preserve original interval metric drains
  and expose actual per-CPU perf data-buffer size. No global exporter or cache.
- Snapshots are partial after cancellation/before symbolizer close. Whole-session
  event/definition loss is unknown. Gadget lookup/reset races producer increments;
  OTel unread loss and submit failures are outside the snapshot.
- Real kernel LRU eviction at the pinned 256-entry layout plus original two-cycle
  pruning verified by `make kernel-cache-test`.
- `make verify` and normal live capture `build/capture.y8KbEs/` passed. The live
  buffer is 524,288 bytes per CPU; reported zero loss remains explicitly partial.
- Recovery revalidation `build/capture.JSgWon/` passed with 400 terminals, one
  invalidation and zero recovery errors. Backlog validation and `./gradlew build`
  passed after the focused checks. The issue remains open for review.

**Remaining project gates:** broader native submit/transport-loss injection,
loss-drain finality, memory/overhead measurements, runtime/JIT/thread matrix,
HotSpot scope/BCI quality and optional Kubescape integration. This issue does not
claim these larger gates are complete.

---
<!-- id: issue-20260930-165600-837-expose-agentless-collection-health-from-existing-r  file: issue-20260930-165600-837-expose-agentless-collection-health-from-existing-r.md -->
