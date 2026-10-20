---
title: "Retain ambiguous benchmark container acquisition ownership"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/internal/benchmark/runner.go"
  - "experiments/hotspot-agentless/internal/driver/ownership.go"
  - "experiments/hotspot-agentless/internal/candidate/runner.go"
target_symbols:
  - "runOne"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain ambiguous benchmark container acquisition ownership

**Context:**
Connected HotSpot remediation review finds target docker run -d and collector create clear their receipt strings on client errors (runner.go lines 192-195 and 234-237), without named/session-labelled recovery requests. A remote success after client timeout is therefore not represented by the shared known-ID retirement helper. Candidate acquisition is fixed separately; benchmark acquisition still needs its own scoped change.

**Needed:**
1. Use actual receipts and qualified name/session recovery for benchmark acquisition, retain unknown outcomes and original failures, and inject late-create controls before claiming remote retirement.

**Execution:** The shared candidate implementation moves to the driver package;
both callers retain owned receipts and commands and publish only after retirement.
Host/race, actual benchmark publication failure and rootful lifecycle checks pass;
see [continuation evidence](../../../plans/2026-10-17-hotspot-benchmark-ownership-evidence.md).
Full Go host/race160tests, actual shared/benchmark lifecycle and failed-publication
checks, six live candidate regressions and the complete build pass. Resolved for
acquisition/retirement/publication integrity. Full epoch-zero readiness refusal is
retained under separate issue-20261016-233030-441, not converted into qualification success.

---
<!-- id: issue-20261016-225418-065-retain-ambiguous-benchmark-container-acquisition-o  file: issue-20261016-225418-065-retain-ambiguous-benchmark-container-acquisition-o.md -->
