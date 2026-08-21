---
title: "Reject process-wide USER_NOTIF during assessment"
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
related_thread: 3796525657
---

# 🟡 [Severity: MEDIUM]: Reject process-wide USER_NOTIF during assessment

**Context:** `assessOnProcess()` can report `installable=true` for a process-wide policy containing `ACT_NOTIFY` whenever the independent TSYNC and USER_NOTIF probes pass, but installation later unconditionally throws because process-wide supervised filters are unsupported. This combination would also require the mutually exclusive NEW_LISTENER and TSYNC modes.

**Problem:**
- assessOnProcess reports installable=true
- Installation throws for process-wide USER_NOTIF
- NEW_LISTENER and TSYNC modes mutually exclusive

**Impact:**
- Preflight passes but installation fails
- Inconsistent assessment

**Needed:**
1. Add blocking reason when processWide && userNotifRequired
2. Check for mutually exclusive modes
3. Make assessment consistent with installation

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3796525657
