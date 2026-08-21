---
title: "Exclude descriptor-only calls from path completeness"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilerSessionApiTest.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRT_kwDOScnnEM6a-1F2
---

# 🟡 [Severity: MEDIUM]: Exclude descriptor-only calls from path completeness

**Context:** `fstat`, `fchmod`, and `fchown` take only a file descriptor. If they are in `pathBearingNames`, `inferPaths()` counts a missing pathname as failure and marks an otherwise complete descendant-strace profile incomplete.

**Do:** Remove `FCHMOD`, `FCHOWN`, and `FSTAT` from `ProfilingCoverage.pathBearingNames`. Keep `FCHMODAT` / `FCHOWNAT` / `FSTATAT` — those can take a path.

**Tests:** `FSTAT` / `FCHMOD` / `FCHOWN` observations with empty `paths` must not yield `PathResolutionQuality.FAILED` and must not by themselves make `complete == false`.
