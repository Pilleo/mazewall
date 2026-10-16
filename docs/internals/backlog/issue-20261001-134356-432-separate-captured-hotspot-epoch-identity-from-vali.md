---
title: "Separate captured HotSpot epoch identity from validation outcome"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-design.md"
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-plan.md"
  - "experiments/hotspot-agentless/results/identity-investigation.md"
target_symbols:
  - "HotSpotIdentity"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Separate captured HotSpot epoch identity from validation outcome

**Context:**
Second review found that the plan changes Trace.HotSpotIdentity from captured stable evidence to a changed/read-error rejection while strict lookup requires event-definition state agreement. A valid delayed event can therefore become a fatal identity mismatch instead of explicit epoch unavailability, and captured evidence can be overwritten.

**Needed:**
1. Define immutable captured evidence separately from decoder validation status and test stable capture followed by a changed-epoch rejected definition.
2. State lookup ordering explicitly: PID/TID/reference and captured epoch agreement, then validation outcome; a semantic boundary rejection is unavailable while a true captured-identity conflict is fatal.

**Counterexample:** event capture is stable epoch 2; deferred decoding observes
epoch 4. Task 4 currently replaces Trace.HotSpotIdentity with a changed rejection,
while Task 5 requires event/definition state agreement. The plan does not define
how stable-vs-changed can preserve identity while returning semantic unavailability.
This is a contract ambiguity in an unimplemented feature, not a live failure.

**Closure evidence:** one immutable capture record and separate validation result
survive storage/waiter delivery. Test stable capture/rejected decode, actual
wrong captured epoch, and already validated historical definition reuse.

**Implemented; independent review pending:** `libpf.Trace.HotSpotIdentity` remains
captured evidence and `HotSpotValidation` carries the independent result/cause.
The Gadget cache stores both; lookup compares stable captured epochs before
returning validation rejection. Tests verify changed-epoch unavailability,
genuine identity conflicts, immutable historical snapshots and rejected
frame-cache admission. Host/race and useful live unloading gates pass.

---
<!-- id: issue-20261001-134356-432-separate-captured-hotspot-epoch-identity-from-vali  file: issue-20261001-134356-432-separate-captured-hotspot-epoch-identity-from-vali.md -->
