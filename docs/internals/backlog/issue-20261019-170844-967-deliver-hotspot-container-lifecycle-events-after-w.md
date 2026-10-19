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
C1 whole-node review reproduced late registration missing add/remove callbacks and ineffective unregister because StartContainerCollection freezes third-party callbacks. Actual current-main port retains the same boundary. Node-agent source is separate from MazeWall; proof is C1-review/watcher-repro.log.

**Needed:**
1. Fix the actual live subscription/dispatch and initial replay ordering boundary; preserve baseline receivers/selectors and prove late add, remove, re-add and real unregister with race checks.

---
<!-- id: issue-20261019-170844-967-deliver-hotspot-container-lifecycle-events-after-w  file: issue-20261019-170844-967-deliver-hotspot-container-lifecycle-events-after-w.md -->
