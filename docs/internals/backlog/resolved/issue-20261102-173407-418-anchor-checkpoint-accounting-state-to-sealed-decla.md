---
title: "Anchor checkpoint accounting state to sealed declarations"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_bootstrap_accounting"
open_questions: false
---

# 🟡 [Severity: HIGH]: Anchor checkpoint accounting state to sealed declarations

## Resolution — 2026-11-02

The producer now anchors the actual accounting map/counter/lock identities at
construction and checks their exact shapes, sealed key-to-item associations and
selected strict counter bounds before traversal or binding capture. Replaced
storage, a genuine declaration at another key and pre-binding bound inflation
cannot be adopted as the baseline. Legitimate owned spent state remains tracked.

The independent cases ran RED before correction; the final 14-subcase matrix
and eleven-test covering run passed (166.269 seconds). Scoped independent
re-review marked finding 1 ADDRESSED with no new important fix-diff breakage.
Evidence is in `.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/envelope-binding-implementation.md`,
`envelope-binding-review.md`, `envelope-binding-rereview.md`, and retained
`/tmp/opencode/profiler-resume-dfx7y2wp/envelope-binding-fix1-review-6ufshl3w/`.
Reviewed corrected entry SHA256:
`216b665fbfe281f8b4231551026e05d3bb3dfc072495a096dafbbaf294a8863f`.
This closes the bounded accounting defect, not M1 or live qualification.

**Context:**
P1: binding currently captures mutable accounting maps, locks and counts at first use without joining key-associated items and selected bounds to the sealed contract. A genuine adapter declaration can replace the legacy map value, or a count can be raised before binding and then accepted. Review: .superpowers/sdd/2026-11-02-profiler-delivery-critical-path/envelope-binding-review.md. No operation/provider authority is released by this inert step.

**Needed:**
1. Anchor actual construction identities and validate sealed key-to-item associations and strict selected counter bounds before traversal or E effects; reproduce pre-binding substitution/reset independently and obtain scoped re-review.

---
<!-- id: issue-20261102-173407-418-anchor-checkpoint-accounting-state-to-sealed-decla  file: issue-20261102-173407-418-anchor-checkpoint-accounting-state-to-sealed-decla.md -->
