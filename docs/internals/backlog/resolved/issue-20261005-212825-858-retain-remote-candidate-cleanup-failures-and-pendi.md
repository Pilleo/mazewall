---
title: "Retain remote candidate cleanup failures and pending container ownership"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/internal/candidate/runner.go"
  - "experiments/hotspot-agentless/internal/candidate/ownership.go"
  - "experiments/hotspot-agentless/internal/driver/process.go"
target_symbols:
  - "candidateOwners"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain remote candidate cleanup failures and pending container ownership

**Context:**
Review reproduces failed docker rm ignored while wrapper waits succeed; timeout of removal can skip joins and replace the original failure

**Needed:**
1. Extract checked remote owner retirement with injected failure tests and preserve original failure and pending owner evidence

**Execution:** Go receipt/recovery/retirement, fault checks and actual rootful
target/helper plus six-case qualification evidence are recorded in
[remediation evidence](../../../plans/2026-10-05-hotspot-review-remediation-evidence.md).
Faults retain original causes and actual pending handles; checked create/start/
join/remove/absence paths pass, results follow retirement and the full build passes.
Resolved for candidate acquisition; the separate benchmark acquisition gap remains
registered as issue-20261016-225418-065.

---
<!-- id: issue-20261005-212825-858-retain-remote-candidate-cleanup-failures-and-pendi  file: issue-20261005-212825-858-retain-remote-candidate-cleanup-failures-and-pendi.md -->
