---
title: "Deliver HotSpot container lifecycle events after watcher startup"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "ContainerWatcher"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Deliver HotSpot container lifecycle events after watcher startup

**Context:**
Original review context, before the local corrections below:
C1 whole-node review reproduced late registration missing add/remove callbacks and ineffective unregister because StartContainerCollection freezes third-party callbacks. Actual current-main port retains the same boundary. Node-agent source is separate from MazeWall; proof is C1-review/watcher-repro.log.

**Needed:**
1. Fix the actual live subscription/dispatch and initial replay ordering boundary; preserve baseline receivers/selectors and prove late add, remove, re-add and real unregister with race checks.

**Local evidence / remaining gate:**
The original C1 defect was corrected in local `2fd19c15`, but whole53-file review
still rejected I1: raw collection replay could resurrect a pointer after watcher
Remove and before another subscriber allowed deletion. That failed review and its
525-pass covered-case receipt remain historical, not retroactively approved.
Scoped node fix `fd108c3b410dbc06e53984e311b6abc9b9a0fe29` orders current watcher
membership/registration/selected replay/live submissions under the existing mutex
and queue, removing command-local stale replay. The actual assembled proof goes
3fail→3pass in both modes; permanent controls include positive initial replay,
pre-delete barriers, selectors/host exemption, unregister/re-add and independent
baseline delivery. Scoped review: I1ADDRESSED, SpecPASS/QualityAPPROVED,0findings.
Final529connected+30covering race parent/subtest PASS records,0failures/0skips,
guards and ordinary binary pass. No new API/budget/authority. See
[current node evidence](../../plans/2026-10-19-hotspot-companion-local-evidence.md#subsequent-whole-feature-review)
for exact current-main/immutable development graph and retained receipts.
Locally addressed only: issue stays open; no publication/merge, released pins,
workspace-off/full privileged CI/image/native/persisted-profile acceptance yet.

---
<!-- id: issue-20261019-170844-967-deliver-hotspot-container-lifecycle-events-after-w  file: issue-20261019-170844-967-deliver-hotspot-container-lifecycle-events-after-w.md -->
