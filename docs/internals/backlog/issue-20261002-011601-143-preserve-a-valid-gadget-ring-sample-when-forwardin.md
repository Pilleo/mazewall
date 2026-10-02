---
title: "Preserve a valid Gadget ring sample when forwarding loss observations"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless"
target_symbols:
  - "receiveEvents"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve a valid Gadget ring sample when forwarding loss observations

**Context:**
Pinned Gadget pkg/operators/ebpf/tracer.go readCb returns valid ringbuf RawSample together with independently polled loss. receiveEvents reports loss then continues, discarding that valid sample. Retained ready burst has 29 missing tokens but 28 forwarded losses and a later target-summary event already delivered.

**Needed:**
1. Reproduce combined loss and payload delivery with a real datasource, preserve the payload, retain loss-only perf handling, and run bounded pressure/stop controls without changing native buffer sizes.

**Implemented evidence (2026-10-02):**
The original dispatch reproduced lost payload/error propagation in a real
datasource test. The local patch reports loss and processes a nonempty payload;
empty/loss-only reads emit no fabricated event. Race tests pass, and a genuine
4 KiB ring-pressure test delivered all 255 valid payloads alongside 745 native
output failures out of 1000 attempts. Three normal ready 1200-operation burst
repeats captured all marked tokens; a higher-pressure 10000-operation run retained
4569 missing tokens and exactly 4569 forwarded loss observations, with a later
same-thread target-summary event delivered. Equality in that run is not general
loss proof. Native buffers, producer/cache bounds and loss completeness are unchanged.
See [`event-read-loss.md`](../../../experiments/hotspot-agentless/results/event-read-loss.md).
Keep open for independent patch review; native finality remains a separate gate.

---
<!-- id: issue-20261002-011601-143-preserve-a-valid-gadget-ring-sample-when-forwardin  file: issue-20261002-011601-143-preserve-a-valid-gadget-ring-sample-when-forwardin.md -->
