---
title: "Keep checkpoint provider installation in its original setup phase"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_install_checkpoint_grants"
open_questions: false
---

# 🟡 [Severity: HIGH]: Keep checkpoint provider installation in its original setup phase

**Context:**
Reader integration relaxed retained binding provenance across phases and its intermediate covering run accepted the provider-phase-owner negative: a not-yet-installed provider could be installed after leaving the binding setup phase. This is distinct from reserving future parent data or legitimately using an already installed provider later. Actual RED: /tmp/opencode/reader-integration-root-final.log, case provider-phase-owner, AssertionError closed checkpoint boundary accepted. Reader implementation report records a correction, but final serial verification is still pending; no live qualification is claimed.

**Needed:**
1. Preserve the explicit original binding-phase requirement at actual provider installation while permitting guarded later use of already installed providers; retain the existing provider-phase-owner regression and verify it on final Reader sources before closure.

## Repository review addendum — 2026-11-04 (RET-03)

[Residual finding](../reviews/2026-11-04-repository-review-findings.md#ret-03).
The before-entry phase guard is present at `bootstrap_entry.py:2001–2003`; keep its
original regression and historical correction. A genuine guarded `advance(HISTORY)`
inside the actual constructor/shared-call seam can still leave the original setup
phase before reservation. The advance at `:1858–1862` has no installation-in-progress
guard, and subsequent binding checks intentionally permit later-phase provenance.
This is a connected source ordering defect; integrated seam injection was not run.

Distinguish in-progress installation from later use of an installed provider. Reject
phase advance during installation and validate at resolver/reservation and readiness
boundaries; a final-only check would still allow premature reservation. Add constructor
and shared-call seam negatives with no parent/session reservation where applicable,
retained attempted-once/Member spending/ownership and no Ready/APP effect. Preserve
future-parent DATA and already-installed later-use positives. Context428's separate
authentication slice is source-fixed, but neither it nor this review approves native
recovery, administrative closure or resuming the paused route.

---
<!-- id: issue-20261102-232658-695-keep-checkpoint-provider-installation-in-its-origi  file: issue-20261102-232658-695-keep-checkpoint-provider-installation-in-its-origi.md -->
