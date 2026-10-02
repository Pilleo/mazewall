---
title: "Prove queued shutdown tail and define bounded agentless finalization ownership"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless"
  - "docs/plans/2026-10-02-agentless-finalization-design.md"
target_symbols:
  - "ebpfInstance.Stop"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prove queued shutdown tail and define bounded agentless finalization ownership

**Context:**
Pinned Gadget closes event readers before detaching producer links; its receive loop does not terminate on ErrFlushed. Collector cancellation also stops OTel readers/consumers before enrichment finish, so worker joins cannot prove queued native buffers drained.

**Needed:**
1. Characterize queued native ring/perf tails under Close versus Flush and write a source-backed finalization state/ownership contract before changing capture lifecycle behavior.

**Evidence checkpoint (2026-10-02):**
The race-enabled native fixture proves eight published records become 1 delivered/
7 unconsumed after Close, versus all eight plus explicit flush boundary after
Flush, for ring and perf. Actual Gadget Close with a held datasource callback
completes/joins its owner yet leaves all seven queued tail records unconsumed.
Source-backed design specifies quiesce/flush/acknowledge/join ordering, separate
stop/abort lifetimes, bounded failure and strict component/loss claim semantics.
See [`queued-tail.md`](../../../experiments/hotspot-agentless/results/queued-tail.md)
and the scoped design. Keep open for lifecycle implementation and independent
verification of producer quiescence and final counter coverage.

---
<!-- id: issue-20261002-015217-776-prove-queued-shutdown-tail-and-define-bounded-agen  file: issue-20261002-015217-776-prove-queued-shutdown-tail-and-define-bounded-agen.md -->
