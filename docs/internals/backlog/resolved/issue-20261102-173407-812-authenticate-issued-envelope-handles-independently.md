---
title: "Authenticate issued envelope handles independently of mutable registries"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_validate_checkpoint_envelope_entry"
open_questions: false
---

# 🟡 [Severity: HIGH]: Authenticate issued envelope handles independently of mutable registries

## Resolution — 2026-11-02

Envelope/member issuance now retains original registry identities and requires
exact tuple shape, self-handle, binding and actual issuance-event identity before
indexing or charging. Agreement between two writable associations is no longer
accepted as issuance proof. Rekeyed entries, replacement registries and foreign
entry traps deny before member charge/body access and without trap execution.

The independent cases ran RED before correction; the final 14-subcase matrix
and eleven-test covering run passed (166.269 seconds). Scoped independent
re-review marked finding 2 ADDRESSED with no new important fix-diff breakage.
Evidence is in `.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/envelope-binding-implementation.md`,
`envelope-binding-review.md`, `envelope-binding-rereview.md`, and retained
`/tmp/opencode/profiler-resume-dfx7y2wp/envelope-binding-fix1-review-6ufshl3w/`.
Reviewed corrected entry SHA256:
`216b665fbfe281f8b4231551026e05d3bb3dfc072495a096dafbbaf294a8863f`.
This closes the bounded issuance defect, not provider/runner integration.

**Context:**
P1: two matching mutable registries can rekey a genuine envelope entry to an unissued handle or accept a matching foreign entry before tuple validation. Source custody alone does not prove returned token identity. Review: .superpowers/sdd/2026-11-02-profiler-delivery-critical-path/envelope-binding-review.md. No operation/provider authority is released by this inert step.

**Needed:**
1. Anchor original registries, validate tuple and self-handle before indexing, join actual entry to an independent acquisition/issuance witness, reproduce rekey/map/entry traps and obtain scoped re-review.

---
<!-- id: issue-20261102-173407-812-authenticate-issued-envelope-handles-independently  file: issue-20261102-173407-812-authenticate-issued-envelope-handles-independently.md -->
