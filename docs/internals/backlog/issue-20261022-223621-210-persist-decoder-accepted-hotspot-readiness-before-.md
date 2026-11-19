---
title: "Persist decoder-accepted HotSpot readiness before workload gates"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "CollectorRunner"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Persist decoder-accepted HotSpot readiness before workload gates

**Context:**
The existing node-agent controller enters Running before collector_ready and previously persisted captures only after the window ends. The approved two-consumer acceptance runner cannot safely gate marked work from generic status or elapsed time. No native readiness is inferred from admission, a PID file, or a guessed session.

**Needed:**
1. Reuse the existing bounded evidence writer for a distinct target/session-bound collector_ready receipt emitted only after shared-decoder acceptance; retain false authority flags, permission causes, existing limits and physical ownership. Prove pre-final publication, rejected-ready absence, and write-failure cleanup with disposable host children before any separately authorized native/cluster run.

**Local source correction (2026-10-23):** Node-agent reuses its real collector
reader and existing evidence writer; `Run`/`Runner` remain compatible. A private
ready-capable adapter wires only decoder-accepted records to a separate16KiB
known-field document, no-replace manifest and the unchanged256MiB node quota.
Readiness does not certify capture completion or a continuing target lease.

**Host evidence:** RED logs `/tmp/opencode/hotspot-ready-red-20261023.log` and
`/tmp/opencode/hotspot-controller-ready-red-20261023.log` reproduce the missing
receipt, ignored write failure and missing pre-final controller output. Focused
race checks then pass in `/tmp/opencode/hotspot-controller-ready-green-20261023.log`.
The full companion and acceptance-source race suite passes in
`/tmp/opencode/hotspot-companion-ready-race-20261023.log` (development workspace,
published bridge v0.2.0/local typed storage; not a released graph).

Native/cluster qualification, reviewed release selection and actual persisted
API readback remain outstanding. No live receipt or execution grant is invented;
this item stays open for that integration/review evidence.

---
<!-- id: issue-20261022-223621-210-persist-decoder-accepted-hotspot-readiness-before-  file: issue-20261022-223621-210-persist-decoder-accepted-hotspot-readiness-before-.md -->
