---
title: "Reject exception metadata callbacks before live recovery formatting"
severity: "HIGH"
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

# 🟡 [Severity: HIGH]: Reject exception metadata callbacks before live recovery formatting

**Context:**
Resolved by scoped persistence fix round1. Capture now uses builtin type
descriptors and class dictionaries, validates exact namespace keys and labels,
selects supported formatter identities and validates/funds all OSError fields
before formatting. Connected metaclass-equality and spoofed-class probes are
also rejected without application callbacks. Original/partial evidence and
unused authority survive rejection.

Actual RED precedes GREEN: the corrected matrix's13 recovery probes passed,
six covering tests passed and63 connected tests passed on entry SHA256
`5fbc4a3c1769e2727b024f92f09e082652f31e13b84ecb4e9e17d5c5a3f52190`,
test SHA256 `820536ae3009802aaefa6642bfccc7827c255107d2aefc92a388523357065424`.
Complete sources/diff/commands/logs:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-nfbtkbyk/`.
The independent `live-recovery-persistence-fix1-rereview.md` in
`.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/` marks148 ADDRESSED
and finds no new Important/Critical fix-diff regression. This closes the bounded
capture defect; provider/native qualification remains outstanding and the
historical recovery route is paused.

**Original context:**
Scoped persistence review finding1: exact OSError args and filename checks leave errno/strerror conversion callbacks and unbounded native messages unchecked; exception class module/name/formatter probes can invoke application equality/descriptors before validating labels. Evidence: live-recovery-persistence-review.md finding1, final entry lines3501-3530, source SHA8fc1863f4aa463702d48156b6ff82cc8e80c273623b9c839f4c33aaf5f002fae.

**Needed:**
1. Add actual-recovery regressions for errno/strerror callbacks, non-string class labels/descriptors and oversized native messages. Validate and fund every supported formatter field without executing application callbacks; retain original error, unused authority and no writer output.

---
<!-- id: issue-20261103-090101-148-reject-exception-metadata-callbacks-before-live-re  file: issue-20261103-090101-148-reject-exception-metadata-callbacks-before-live-re.md -->
