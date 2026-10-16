---
title: "Retain remote candidate cleanup failures and pending container ownership"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/internal/candidate/runner.go"
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
[remediation evidence](../../plans/2026-10-05-hotspot-review-remediation-evidence.md).
Final review/gates remain before archival.

---
<!-- id: issue-20261005-212825-858-retain-remote-candidate-cleanup-failures-and-pendi  file: issue-20261005-212825-858-retain-remote-candidate-cleanup-failures-and-pendi.md -->
