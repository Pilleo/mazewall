---
title: "Bind both adapter publication registries to the produced module"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_validate_setup_entry"
open_questions: false
---

# 🟡 [Severity: HIGH]: Bind both adapter publication registries to the produced module

## Resolution — 2026-11-02

Adapter entry validation now requires both the canonical `sys.modules` entry
and the producer's publication map to be the actual produced module, before
handle issuance and on later accounting/budget validation. Matching foreign
replacements no longer satisfy ownership. Failure cleanup remains identity-only
and preserves foreign replacement objects.

The independent publication-only pre-issuance and post-admission cases first
reproduced acceptance under the old code, then passed their denial assertions
after correction without record corruption masking the result. The focused
five-subcase regression and seven covering tests passed on the amended source;
the covering run completed in 130.610 seconds. Scoped independent re-review
marked finding 1 ADDRESSED, with no new critical/important fix-diff breakage.

Evidence: `.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/adapter-admission-implementation.md`,
`adapter-admission-review.md`, `adapter-admission-rereview.md`; retained source,
diff and RED/GREEN logs under
`/tmp/opencode/profiler-resume-dfx7y2wp/adapter-admission-fix1-review-nz1cbyis/`.
Reviewed corrected entry SHA256:
`2c459248d20db676549cd2ebd7eb0e5437c1454e65a76721ce826d084daf2df4`.
This closes the scoped ownership defect, not M1 or live qualification.

**Context:**
Unit1 review P1: matching foreign replacements in sys.modules and the private bootstrap publication map can evade validation of the separately retained entry. Existing failure test also mutates the record and masks this case. Definitions are inert; no operation or provider authorization is established. Evidence: .superpowers/sdd/2026-11-02-profiler-delivery-critical-path/adapter-admission-review.md

**Needed:**
1. Reproduce independent pre-issuance and post-admission publication-only replacement; require both registries to be the actual produced module and retain identity-only failure cleanup, then run scoped re-review.

---
<!-- id: issue-20261102-163311-043-bind-both-adapter-publication-registries-to-the-pr  file: issue-20261102-163311-043-bind-both-adapter-publication-registries-to-the-pr.md -->
