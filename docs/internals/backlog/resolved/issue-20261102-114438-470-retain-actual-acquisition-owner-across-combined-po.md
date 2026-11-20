---
title: "Retain actual acquisition owner across combined post-validation failure"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_application_acquisition_endpoint"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain actual acquisition owner across combined post-validation failure

**Context:**
Independent APP packet review found that a real custody failure followed by endpoint post-validation failure leaves the event owner None. The finally exception obscures the original observed failure into implicit context, while recovery walks only observed/group/explicit cause. The original object can remain indirectly reachable; the defect is loss of the explicit actual-owner witness required for failure custody. Review: /tmp/opencode/profiler-resume-dfx7y2wp/i1c-application-emitter-review.md. No live activation occurred.

**Needed:**
1. Reproduce the combined read/post-validation fault; preserve both errors with an explicit group or cause, retain the original observed owner and cleanup without EOF fabrication or refunds, then obtain scoped re-review.

## Resolution — 2026-11-02

The endpoint now explicitly groups the primary custody failure and any subsequent
validation failure. Existing recovery can therefore retain the original observed
owner in the acquisition event. Successful returns still record the actual owner
before post-validation, and validation failure cannot issue a source handle or
refund a debit.

The combined-fault regression failed before the correction and passes after it;
all 35 entry tests pass. Scoped independent re-review closed the P2 finding.
Its P3 fixture discrepancy was then corrected by deleting the inline native
binding restoration: all failure-witness assertions now precede the existing
`finally` cleanup. Focused and full entry checks pass; an independent byte-hash
reconstruction confirms that final test change is exactly one deleted line and
production source is unchanged.

Evidence under `/tmp/opencode/profiler-resume-dfx7y2wp/`:
`i1c-application-emitter.md`, `i1c-application-emitter-review.md`,
`i1c-application-emitter-rereview.md` and
`i1c-application-fixture-delta-check.json`.
Corrected entry source SHA256:
`ebb37f79ca4b6a8b64415e728c391b08a1fb68ca6852e176ae5afe7aef996f5e`.
This closes failure-custody correctness in the compiler core, not live
qualification, helper/Reader integration or delivery authority.

---
<!-- id: issue-20261102-114438-470-retain-actual-acquisition-owner-across-combined-po  file: issue-20261102-114438-470-retain-actual-acquisition-owner-across-combined-po.md -->
