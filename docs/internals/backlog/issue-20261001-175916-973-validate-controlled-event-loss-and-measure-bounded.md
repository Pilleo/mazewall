---
title: "Validate controlled event loss and measure bounded agentless capture overhead"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless"
  - "docs/plans/2026-10-01-agentless-loss-benchmark-plan.md"
target_symbols:
  - "Run"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate controlled event loss and measure bounded agentless capture overhead

**Context:**
The standalone capture has partial health counters and useful epoch correctness, but no three-mode repeated benchmark or controlled event-gap integrity report. Loss observations must remain partial until native drain/finality is proved.

**Needed:**
1. Implement pinned baseline/events-only/full benchmark runs with exact operation tokens, latency/CPU/RSS provenance, honest missing-event classification and an isolated drop-event gadget control.

**Implemented evidence (2026-10-02):**
Three-mode capture/report/runner, isolated token-42 discard gadget and repeated
measurements are implemented locally. Report integrity rejects fabricated
terminal counters, events-mode profiler evidence and final loss claims. The final
isolated drop control required exactly `[42]` missing; an attempt with `[42,82]`
was rejected and retained. Paced ready eight-route repeats all observed/correctly
attributed 1200/1200 measured segments. Cold burst useful coverage failed, and the
third ready full burst lost 29 tokens despite eight-route pre-gate readiness.
CPU/RSS windows include startup/discovery/preparation, not workload-only overhead;
kernel memory remains unmeasured. See
[`loss-benchmark.md`](../../../experiments/hotspot-agentless/results/loss-benchmark.md).
Keep open for burst transport/finality and independent measurement review.

---
<!-- id: issue-20261001-175916-973-validate-controlled-event-loss-and-measure-bounded  file: issue-20261001-175916-973-validate-controlled-event-loss-and-measure-bounded.md -->
