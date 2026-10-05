---
title: "Retain remote candidate cleanup failures and pending container ownership"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/scripts/run-capability-candidates.py"
target_symbols:
  - "run"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain remote candidate cleanup failures and pending container ownership

**Context:**
Review reproduces failed docker rm ignored while wrapper waits succeed; timeout of removal can skip joins and replace the original failure

**Needed:**
1. Extract checked remote owner retirement with injected failure tests and preserve original failure and pending owner evidence

---
<!-- id: issue-20261005-212825-858-retain-remote-candidate-cleanup-failures-and-pendi  file: issue-20261005-212825-858-retain-remote-candidate-cleanup-failures-and-pendi.md -->
