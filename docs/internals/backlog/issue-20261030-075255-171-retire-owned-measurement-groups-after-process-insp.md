---
title: "Retire owned measurement groups after process inspection fails"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "benchmarks/integration-boundaries/pitch/measure.py"
target_symbols:
  - "retire"
open_questions: false
---

# 🟡 [Severity: HIGH]: Retire owned measurement groups after process inspection fails

> Superseded by explicit operator direction to remove the pitch-only measurement
> driver. Its ownership nuance and the evidence below are historical; the optional
> global workflow reuses the existing Linux resource probe instead. See
> [the removal/workflow scope](issue-20261030-225329-595-replace-pitch-only-measurements-with-optional-glob.md).

**Context:**
Approved inline follow-up Task2. Initial/later proc inspection failures currently abandon genuine owned groups before signal/reap. Preserve WNOWAIT ownership and2sTERM/5sKILL/5sreap bounds; fail measurement on unverifiable cleanup and preserve errors. No generic supervisor, guessed PID authority or permission-error swallowing.

**Needed:**
1. Add real-process RED regressions for initial/later inspection failure, reaped anchor and denied emergency signal; fix shared retire only, run10host cases, then final source-bound/full gates.

## Disposition

Historical producer `73ec49e83e4bc8bd5d95d99995215e1d7f50a834` passed the10-case
real-process suite and full/source gates. Cleanup retained the unreaped acquired
leader, bounded signals/reaping and original failures; failed inspection never
qualified measurements. Denied emergency signalling could delay leader reaping.

The driver and its tests are now removed, so that deferred nuance is not active
benchmark code or a claimed fix to the global harness. The replacement uses the
existing resource probe rather than inventing PID authority or a new supervisor.
This historical item stays open for managed landing; no new work is implied here.

---
<!-- id: issue-20261030-075255-171-retire-owned-measurement-groups-after-process-insp  file: issue-20261030-075255-171-retire-owned-measurement-groups-after-process-insp.md -->
