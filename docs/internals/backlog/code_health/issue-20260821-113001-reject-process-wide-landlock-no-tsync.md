---
title: "Reject process-wide Landlock when TSYNC is unavailable"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/InstallationAssessment.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819324208
---

# 🟡 [Severity: MEDIUM]: Reject process-wide Landlock when TSYNC is unavailable

**Context:** On kernels with Landlock ABI 1-7, `landlockSupported` is true while `landlockTsyncSupported` is false, so this check adds no blocking reason and `assessOnProcess()` reports a path-bearing policy as installable. The actual `LandlockSession` rejects the same installation under the default `FAIL` fallback because process-wide Landlock requires TSYNC.

**Problem:**
- landlockSupported true but landlockTsyncSupported false
- No blocking reason added
- assessOnProcess reports installable
- LandlockSession rejects installation

**Impact:**
- Assessment passes but installation fails
- Inconsistent behavior

**Needed:**
1. Add blocking reason when TSYNC unavailable
2. Check landlockTsyncSupported in assessment
3. Make assessment consistent with installation

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819324208
