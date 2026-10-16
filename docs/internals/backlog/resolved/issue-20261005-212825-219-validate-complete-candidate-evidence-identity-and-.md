---
title: "Validate complete candidate evidence identity and terminal pairing"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/internal/candidate"
target_symbols:
  - "validateCapture"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate complete candidate evidence identity and terminal pairing

**Context:**
Review reproduces schema99 foreign occurrence IDs and wrong-process carrier records being accepted; Loom carrier-only path skips terminal validation and oracle duplicates overwrite

**Needed:**
1. Add strict shared schema session nested identity PID birth full path and terminal pairing tests before verifier hardening

**Execution:** Current Go verifier hardening and host/race regressions are recorded
in the [remediation evidence](../../../plans/2026-10-05-hotspot-review-remediation-evidence.md).
Retained runtime and fresh independently bound virtual captures pass final CLI
revalidation. The full build, ABI gates and seven actual public native cases pass.
Resolved with complete-stream, typed-presence and unchanged qualification limits.

---
<!-- id: issue-20261005-212825-219-validate-complete-candidate-evidence-identity-and-  file: issue-20261005-212825-219-validate-complete-candidate-evidence-identity-and-.md -->
