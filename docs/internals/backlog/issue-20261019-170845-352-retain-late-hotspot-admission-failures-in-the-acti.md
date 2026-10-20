---
title: "Retain late HotSpot admission failures in the active command owner"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "startAgentlessHotspot"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain late HotSpot admission failures in the active command owner

**Context:**
C1 real startup capability-body proof retains a pending feature after actual 10s admission and 2s cleanup waits, then returns EACCES; main nulls its error channel on the earlier admission failure, losing live delivery. Proof is C1-review/startup-repro.log.

**Needed:**
1. Keep servicing a retained feature owner after admission failure; test the actual main owner-routing boundary with joined held body, immediate timeout and later original permission error without stopping baseline processing.

**Local evidence / remaining gate:**
Local node `2fd19c15` keeps a retained feature's failure channel in the real main
owner-routing boundary after admission error. The actual held capability body
proves timeout/pending first, then original EACCES reaches the owner after release;
body/Stop are joined and baseline/disabled controls remain. No probe/registration
or larger10s/2s budget is introduced. Whole-feature review accepted I2; its source/
test bytes remain unchanged in `fd108c3b410dbc06e53984e311b6abc9b9a0fe29`, whose
scoped re-review closes I1 without changing I2. Fresh final529connected race PASS
records include the actual late-EACCES and RuntimeController controls,0failures/
0skips; ordinary binary and guards pass. See
[current node evidence](../../plans/2026-10-19-hotspot-companion-local-evidence.md#subsequent-whole-feature-review)
for source/graph/receipt identities. This issue remains open, locally addressed
but unpushed/unmerged; real released SDK/storage pins, workspace-off/full privileged
CI, images and native/K8s persisted-profile acceptance remain separate gates.

---
<!-- id: issue-20261019-170845-352-retain-late-hotspot-admission-failures-in-the-acti  file: issue-20261019-170845-352-retain-late-hotspot-admission-failures-in-the-acti.md -->
