---
title: "Validate raw Enum state before traversing adapter definitions"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "compile_root"
open_questions: false
---

# 🟡 [Severity: HIGH]: Validate raw Enum state before traversing adapter definitions

## Resolution — 2026-11-02

The finite adapter Enum branch now validates raw class bindings, list types and
elements, typed map/member identities and raw member name/value state before
Enum iteration or overridable member-property access. It does not probe an
unknown descriptor protocol to decide whether an object is safe.

All three pre-admission iterator, foreign-key and descriptor traps first
executed their sentinels under the old code. After correction they deny with
zero callback executions and no retained handle. Genuine Enum admission and
the existing post-admission matrix still pass. The focused five-subcase
regression and seven covering tests passed; the covering run took 130.610
seconds. Scoped independent re-review marked finding 2 ADDRESSED, with no new
critical/important fix-diff breakage.

Evidence: `.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/adapter-admission-implementation.md`,
`adapter-admission-review.md`, `adapter-admission-rereview.md`; retained source,
diff and RED/GREEN logs under
`/tmp/opencode/profiler-resume-dfx7y2wp/adapter-admission-fix1-review-nz1cbyis/`.
Reviewed corrected entry SHA256:
`2c459248d20db676549cd2ebd7eb0e5437c1454e65a76721ce826d084daf2df4`.
This closes the scoped traversal defect, not envelope/runner integration or
live qualification.

**Context:**
Unit1 review P1: pre-admission capture invokes Enum iteration before validating member-name list types and reads member.name before excluding foreign descriptors. Foreign iterators, keys or descriptors may execute before rejection. No live authority or provider is installed. Evidence: .superpowers/sdd/2026-11-02-profiler-delivery-critical-path/adapter-admission-review.md

**Needed:**
1. Reproduce pre-admission iterator, foreign-key and descriptor traps; validate closed raw class, list, map and member state before traversal and prove zero trap execution, then run scoped re-review.

---
<!-- id: issue-20261102-163311-272-validate-raw-enum-state-before-traversing-adapter-  file: issue-20261102-163311-272-validate-raw-enum-state-before-traversing-adapter-.md -->
