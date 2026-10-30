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

**Context:**
Approved inline follow-up Task2. Initial/later proc inspection failures currently abandon genuine owned groups before signal/reap. Preserve WNOWAIT ownership and2sTERM/5sKILL/5sreap bounds; fail measurement on unverifiable cleanup and preserve errors. No generic supervisor, guessed PID authority or permission-error swallowing.

**Needed:**
1. Add real-process RED regressions for initial/later inspection failure, reaped anchor and denied emergency signal; fix shared retire only, run10host cases, then final source-bound/full gates.

## Execution

- [x] Initial/later inspection and emergency-signal regressions RED->GREEN.
- [x] Reaped-anchor no-signal control and all10 Python host cases pass.
- [ ] Final independent review and source/full gates.

Task2 complete: the original suite produced3 expected failures (abandoned/reapless
groups and lost signal-failure context). Shared cleanup now revalidates the acquired
unreaped group before emergency KILL, reaps the leader and propagates the original
inspection failure; denied cleanup retains that cause. The normal2sTERM/5sKILL/5sreap
bounds remain; a KILL deadline already in progress is not restarted. No permission
error is ignored and no failed run becomes accepted data.

Ruling: failed inspection cannot attest group quiescence, and a successful KILL
request is asynchronous. After the already-exited-parent path, the real child took
about1-3ms to become observably dead. Tests independently poll that genuine child's
physical end within the existing5s bound rather than require instantaneous signal
delivery; the production run still fails. Cost if wrong: unknown cleanup remains
an error, never claimed retirement or a silently increased application timeout.

The test observer also handles legitimate ENOENT/ESRCH races while reading an exited
child, matching the existing production inspector without swallowing permission
failures. Logs: `/tmp/opencode/review-followup-cleanup-{red,green}.log`.

---
<!-- id: issue-20261030-075255-171-retire-owned-measurement-groups-after-process-insp  file: issue-20261030-075255-171-retire-owned-measurement-groups-after-process-insp.md -->
