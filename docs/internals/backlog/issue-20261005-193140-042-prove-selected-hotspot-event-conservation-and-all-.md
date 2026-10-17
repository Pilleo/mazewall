---
title: "Prove selected HotSpot event conservation and all-source loss inventory"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/accounting"
  - "docs/plans/2026-10-05-hotspot-loss-accounting-evidence.md"
target_symbols:
  - "Evaluate"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prove selected HotSpot event conservation and all-source loss inventory

**Context:**
Selected event loss needs actual quiescent counters; whole collection remains unknown until all producer and delivery boundaries are proven

**Needed:**
1. Implement overflow-safe conservation and source inventory, qualify actual native counters without promoting policy coverage

Pure models and isolated actual kernel counter qualification implemented. Filtered
openat512attempts/255submits/257reserve failures/255received conserve; native sticky
overflow and all12possibleCPU slots tested. Production counter binding/definition
source denominators and global attestation remain unproved; keep open. See
[evidence](../../plans/2026-10-05-hotspot-loss-accounting-evidence.md).

---
<!-- id: issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-  file: issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-.md -->

## 2026-10-17 Plan04 Task2 Step1 proposed-contract boundary

Decision: **`not_authorized_pending_operator_review`**. The
[selected accounting contract](../../plans/2026-10-17-hotspot-selected-accounting-contract.md)
proposes `selected_counts` PerCPUArray/key4/value32/entries1, acquired
session/PID/birth/map-ID/possible-CPU binding, real detach/flush/receiver and
auxiliary joins before final read, owner-mutex publication/deep copy, and an optional
schema1 selected-only diagnostic. It records actual current patch/bundle/image
identities and deterministic future provenance derivation with all nine pin
consumers; no next patch/image identity exists before approved source edits/builds.

This is contract preparation only, not native implementation approval. Independent
review and explicit operator approval must resolve acquisition timing (current
prepare precedes collection creation), identity/API/publication locking, failure
and pending-owner paths, exact map/schema/provenance compatibility, and the full
negative/pressure/live/public proof gates. Native/BPF/Go/Kotlin/ABI/schema/pins,
generated graphs and historical evidence remain unchanged by this docs-only work.
Existing delivery budgets and production profile stay fixed. Selected accounting
completeness is neither zero loss, global collection completeness nor policy
eligibility. Keep this issue open; host Task1 proof is not production binding proof.
