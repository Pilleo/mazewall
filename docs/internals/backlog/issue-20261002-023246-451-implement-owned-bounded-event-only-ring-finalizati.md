---
title: "Implement owned bounded event-only ring finalization"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless"
  - "docs/plans/2026-10-02-agentless-finalization-design.md"
  - "docs/superpowers/plans/2026-10-02-agentless-event-only-finalization.md"
target_symbols:
  - "ebpfInstance.Stop"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Implement owned bounded event-only ring finalization

**Context:**
Approved finalization design and b11d0d4a native proof require a supported event-only stop owner: retire the single openat writer, flush and join the original ring reader/callbacks, sample native reserve failures after quiescence, then retire readers. Full definition drain and complete loss remain separate gates.

**Needed:**
1. Implement opt-in scoped state/ownership and native queued-tail acceptance with failure/deadline/permission tests, preserve unrelated work and native bounds, and expose honest component observations.

**Implemented evidence (2026-10-02):**
Opt-in `--drain-events` is restricted to structured events-only single-openat-ring
capture. The owned stop retires the writer, flushes/joins reader and callbacks,
samples reserve counters without racing reset polling, and retires owned resources.
Native tests pass queued-tail delivery, positive loss with no later event, no
publication after detach, and timeout retention/repeated-Close behavior. Host
race tests cover unsupported ownership, counter overflow/layout, duplicate wait
cancellation and EPERM/EACCES at each phase. Three 1200-token repeats and the final
guarded live repeat passed; high-pressure and injected-discard loss remain honest
separate dimensions. See
[`event-only-finalization.md`](../../../experiments/hotspot-agentless/results/event-only-finalization.md).
Keep open for independent lifecycle/detach review; full-mode drain and complete
loss remain separate gates.

---
<!-- id: issue-20261002-023246-451-implement-owned-bounded-event-only-ring-finalizati  file: issue-20261002-023246-451-implement-owned-bounded-event-only-ring-finalizati.md -->
