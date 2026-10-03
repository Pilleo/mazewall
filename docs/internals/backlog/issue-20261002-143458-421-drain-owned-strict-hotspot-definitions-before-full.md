---
title: "Drain owned strict HotSpot definitions before full capture retirement"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless"
  - "docs/plans/2026-10-02-agentless-finalization-design.md"
  - "docs/superpowers/plans/2026-10-02-agentless-full-definition-finalization.md"
target_symbols:
  - "startTraceEventMonitor"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Drain owned strict HotSpot definitions before full capture retirement

**Context:**
Owned event-only ring finalization passes. Full strict capture still shares request cancellation with its definition reader/consumer and can discard pending perf definitions before enrichment finishes. The next gate requires producer retirement, explicit perf flush and acknowledged final consumer handoff under the same bounded stop budget.

**Needed:**
1. Implement owned definition reader/consumer drain and coordinated strict full-mode stop with real queued-tail, timeout, permission and live epoch/recovery controls; retain partial native loss.

**Implemented evidence (2026-10-02):**
Optional strict `--drain-full` preserves the owned definition/enrichment/identity
lifetime across graceful requests. The existing event stop owner invokes the full
handoff under its original deadline after event readout, before map retirement.
The profiler retires its native hooks without closing process metadata, flushes
the original perf reader and acknowledges the final consumer callback/channel.
Native race tests deliver all 8 and 5000 queued raw definitions, including crossing
the normal 4096-record batch limit. Permission/cancellation/error causes stay
explicit. Three paced strict repeats resolved all 1200 tokens each; burst repeats
captured all 1200 with 1024 correct segments/176 bounded admission skips each.
Genuine first-definition recovery and strict 80-generation unloading passed with
both component drains observed. See
[`full-definition-finalization.md`](../../../experiments/hotspot-agentless/results/full-definition-finalization.md).
Keep open for independent ownership/producer/loss-domain review. Transport drain
does not prove complete native loss or broader HotSpot lifecycle/BCI fidelity.

---
<!-- id: issue-20261002-143458-421-drain-owned-strict-hotspot-definitions-before-full  file: issue-20261002-143458-421-drain-owned-strict-hotspot-definitions-before-full.md -->
