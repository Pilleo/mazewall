---
title: "Exclude descriptor-only calls from path completeness"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825912167
---

# 🟡 [Severity: MEDIUM]: Exclude descriptor-only calls from path completeness

**Review (2026-08-21):** DUPLICATE of issue-20260821-000006-descriptor-only-syscalls (fixed: FSTAT/FCHMOD/FCHOWN removed from pathBearingNames).

**Context:** Duplicate of `issue-20260821-000006-descriptor-only-syscalls`. Same fix: drop `FSTAT` / `FCHMOD` / `FCHOWN` from `pathBearingNames`.

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912167
