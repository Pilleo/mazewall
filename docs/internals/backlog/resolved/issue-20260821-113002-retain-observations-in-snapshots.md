---
title: "Retain observations in session snapshots"
severity: "MEDIUM"
status: "resolved"
priority: medium
resolved_in_commit: 6b0dd1cf7f0df80413b4987a3600e776aa467e47
resolved_by: "already fixed by commit"
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/MazewallProfiler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819470485
---

# 🟡 [Severity: MEDIUM]: Retain observations in session snapshots

**Context:** After a successful `profile()` call, this rebuilds `lastSnapshot` without passing `result.observations`, so `session.snapshot()` returns the behavior and coverage from the run but an empty raw-observation list. Callers using snapshots for collector correlation, diagnostics, or auditing therefore lose data that is present in the original result.

**Problem:**
- lastSnapshot rebuilt without result.observations
- Empty raw-observation list in snapshot
- Data lost for correlation/diagnostics

**Impact:**
- Loss of observation data in snapshots
- Diagnostics/auditing incomplete

**Needed:**
1. Copy merged observations into snapshot
2. Ensure snapshot contains all observation data

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819470485
