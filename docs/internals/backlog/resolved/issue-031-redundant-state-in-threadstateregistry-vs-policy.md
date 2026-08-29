---
title: "Redundant State in `ThreadStateRegistry` vs `Policy`"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
effort: "medium"
github_issue: 52
paperclip_issue_id: b04b4dc5-64a5-4f34-9713-485ca363868e
paperclip_identifier: MAZ-226
---

# ✅ [Severity: LOW]: Redundant State in `ThreadStateRegistry` vs `Policy`

**Target:** `io.mazewall.enforcer.ThreadStateRegistry`
**Context:** The registry manually tracks `landlockAppliedReads` and `landlockAppliedWrites`, partially duplicating the information already contained within the `Policy` object (DRY violation).
**Needed:** Consolidate state tracking to use the `Policy` object as the single source of truth for applied Landlock restrictions.
