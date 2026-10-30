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

---
<!-- id: issue-20261030-075255-171-retire-owned-measurement-groups-after-process-insp  file: issue-20261030-075255-171-retire-owned-measurement-groups-after-process-insp.md -->
