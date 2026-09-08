---
title: "Type-State Machine for Landlock Ruleset Mutability"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: 783cc945-5358-4aa3-98d8-88a539f23905
paperclip_identifier: MAZ-224
---

# ✅ [RESOLVED]: Type-State Machine for Landlock Ruleset Mutability

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.landlock.Landlock`
**Context:** Landlock follows a strict `Create -> Add Rules -> Restrict Self` lifecycle. Adding a rule after restriction fails silently or errors out.
**Fix:** Implemented `RulesetState` (Building/Sealed) and `LandlockRuleset<S>` type-state wrapper to ensure rules are only added before the ruleset is sealed.
