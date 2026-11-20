---
title: "Check decoded envelope shapes before invoking object protocols"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_acquire_checkpoint_envelope"
open_questions: false
---

# 🟡 [Severity: HIGH]: Check decoded envelope shapes before invoking object protocols

## Resolution — 2026-11-02

Decoded witness validation now establishes exact primitive/container/member-kind
and store-data shapes before comparison or traversal. The finite CPython 3.14
boundary uses a captured trusted referent-inspection binding to establish an
exact built-in dictionary behind a proxy before touching its mapping protocols.
Foreign digest equality and proxy-backing traps deny with zero executions,
retaining the actual acquired owner and spent grants without issuing a handle.

The independent cases ran RED before correction; the final 14-subcase matrix
and eleven-test covering run passed (166.269 seconds). Scoped independent
re-review marked finding 3 ADDRESSED with no new important fix-diff breakage.
Evidence is in `.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/envelope-binding-implementation.md`,
`envelope-binding-review.md`, `envelope-binding-rereview.md`, and retained
`/tmp/opencode/profiler-resume-dfx7y2wp/envelope-binding-fix1-review-6ufshl3w/`.
Reviewed corrected entry SHA256:
`216b665fbfe281f8b4231551026e05d3bb3dfc072495a096dafbbaf294a8863f`.
This closes the bounded callback-ordering defect, not native qualification.

**Context:**
P1: decoded slots are captured before complete inert shape validation. A non-string digest invokes foreign equality and MappingProxyType can wrap a dict subclass whose items method executes during validation. Review: .superpowers/sdd/2026-11-02-profiler-delivery-critical-path/envelope-binding-review.md. No operation/provider authority is released by this inert step.

**Needed:**
1. Prove zero execution for decoder-return digest and backing-map traps; validate exact primitive and trusted backing-container shape before equality/traversal, preserve spent grants and actual custody, then obtain scoped re-review.

---
<!-- id: issue-20261102-173408-255-check-decoded-envelope-shapes-before-invoking-obje  file: issue-20261102-173408-255-check-decoded-envelope-shapes-before-invoking-obje.md -->
