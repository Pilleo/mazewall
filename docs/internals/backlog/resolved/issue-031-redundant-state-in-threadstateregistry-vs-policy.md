---
title: "Redundant State in `ThreadStateRegistry` vs `Policy`"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "medium"
github_issue: 52
---

# ✅ [Severity: LOW]: Redundant State in `ThreadStateRegistry` vs `Policy`

**Target:** `io.mazewall.enforcer.ThreadStateRegistry`
**Context:** The registry manually tracks `landlockAppliedReads` and `landlockAppliedWrites`, partially duplicating the information already contained within the `Policy` object (DRY violation).
**Needed:** Consolidate state tracking to use the `Policy` object as the single source of truth for applied Landlock restrictions.
