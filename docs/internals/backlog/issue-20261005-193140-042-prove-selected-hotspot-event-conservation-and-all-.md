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
