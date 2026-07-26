---
title: "Landlock.applyRestrictiveBarrier() Silent Fail-Open"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Landlock.applyRestrictiveBarrier() Silent Fail-Open

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.landlock.Landlock.kt`
**Context:** The method ignored the return values of `prctl(PR_SET_NO_NEW_PRIVS)` and the `landlock_restrict_self` syscall. If the kernel fails to apply the ruleset (e.g. invalid FD, EPERM), the method returned silently, and the `IterativeProfiler` continued running WITHOUT filesystem containment, leading to zero discovered paths.
**Fix:** Updated to use `getOrThrow("landlock_restrict_self")` in `enforceRuleset`, ensuring failures are caught and reported.
