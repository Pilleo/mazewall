---
title: "Admit acquisition shape validation before allocation"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_LiveExecution._capture_live_recovery"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Admit acquisition shape validation before allocation

**Context:**
Resolved by scoped persistence fix round1. Exact dictionary type and three/four
key-count checks now precede input-sized work. Bounded shape work is admitted,
and exact string keys are validated before set construction or equality. The
oversized-dictionary and callback-key regressions preserve original/partial
evidence, no output and unused authority on rejection.

Actual RED precedes GREEN: the corrected matrix's13 recovery probes passed,
six covering tests passed and63 connected tests passed on entry SHA256
`5fbc4a3c1769e2727b024f92f09e082652f31e13b84ecb4e9e17d5c5a3f52190`,
test SHA256 `820536ae3009802aaefa6642bfccc7827c255107d2aefc92a388523357065424`.
Complete sources/diff/commands/logs:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-nfbtkbyk/`.
The independent `live-recovery-persistence-fix1-rereview.md` in
`.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/` marks565 ADDRESSED
and finds no new Important/Critical fix-diff regression. This closes diagnostic
shape admission only; no physical/native qualification or historical-route
authority is supplied.

**Original context:**
Scoped persistence review finding2: _capture_live_recovery executes set(item) before checking the exact dictionary key count/types or admitting its input-sized traversal and allocation. One oversized acquisition dictionary bypasses the diagnostic admission order. Evidence: live-recovery-persistence-review.md finding2, final entry lines3531-3539 and live_contract.py314-324.

**Needed:**
1. Reject invalid exact-dictionary lengths in constant time, admit bounded shape work and validate exact string keys before set/equality operations. Add oversized-dictionary and callback-key regressions with a small remaining reserve, unused authority, no output and preserved original/partial evidence.

---
<!-- id: issue-20261103-090101-565-admit-acquisition-shape-validation-before-allocati  file: issue-20261103-090101-565-admit-acquisition-shape-validation-before-allocati.md -->
