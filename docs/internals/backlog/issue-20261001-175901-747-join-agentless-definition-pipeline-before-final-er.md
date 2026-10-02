---
title: "Join agentless definition pipeline before final error and health sampling"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/patches"
  - "experiments/hotspot-agentless/scripts/prepare-gadget.py"
  - "experiments/hotspot-agentless/internal/capture/**"
  - "experiments/hotspot-agentless/internal/model/**"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/results/**"
  - "experiments/hotspot-agentless/README.md"
  - "docs/plans/2026-10-01-agentless-loss-benchmark-plan.md"
target_symbols:
  - "startTraceEventMonitor"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Join agentless definition pipeline before final error and health sampling

**Context:**
Post-implementation review found cancellation can leave an unbuffered definition send blocked after the consumer exits, while resolver Close does not join an in-flight handler. The final fatal/health snapshot can precede its last callback.

**Needed:**
1. Add cancellation-aware delivery, owned reader and consumer completion, and a bounded optional stop capability before sampling final summary; do not claim native buffers drained.

**Implemented evidence (2026-10-02):**
Cancellation-aware trace/barrier delivery, owned completion, optional bounded stop
and final sampling order are implemented locally. Channel-gated race tests cover
consumer cancellation, held final callbacks and timeout. Strict unloading and
genuine definition-loss recovery passed after the change. Live summaries explicitly
report joined workers and `buffers_drained=false`; incomplete joins are session
errors and unjoined partial snapshots. See
[`loss-benchmark.md`](../../../experiments/hotspot-agentless/results/loss-benchmark.md).
Keep open for independent review and native producer-buffer finality.

---
<!-- id: issue-20261001-175901-747-join-agentless-definition-pipeline-before-final-er  file: issue-20261001-175901-747-join-agentless-definition-pipeline-before-final-er.md -->
