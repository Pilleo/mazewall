---
title: "Integrate validated HotSpot agentless backend as an explicit MazeWall profiler strategy"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/MazewallProfiler.kt"
  - "docs/superpowers/plans/2026-10-03-mazewall-hotspot-profiler-integration.md"
target_symbols:
  - "ProfileStrategy"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Integrate validated HotSpot agentless backend as an explicit MazeWall profiler strategy

**Context:**
The validated external collector is standalone. MazeWall-first integration needs typed loss-aware evidence, reproducible collector assets, owned process/session lifecycle and an additive explicit strategy without assigning USER_NOTIF/Tier E completeness or invocation identity.

**Needed:**
1. Review and approve the draft MazeWall-first integration plan, then implement the first typed evidence/coverage contract milestone with replay and compatibility tests.

Planning only. The draft starts with typed bounded evidence and incomplete coverage,
then canonical Go/assets, production readiness/session ownership, Kotlin supervision,
explicit strategy/result compatibility, live acceptance and supported distribution.
Process attachment precedes current-PID workload convenience; the existing JVM must
already satisfy the pinned interpreter/Serial profile. Public API changes and
implementation require operator review of this draft. Kubescape is independent.

**Resolution:** All seven operator-approved integration milestones are complete:
bounded typed evidence, canonical reproducible assets, strict readiness/lifecycle,
owned Kotlin supervision, additive explicit API, six-case native acceptance and
verified local distribution/installed public example. Whole-session coverage remains
incomplete; dense interpreter admission/readout is a separate open follow-up. See
[final evidence](../../../plans/2026-10-05-mazewall-hotspot-distribution-milestone.md).

---
<!-- id: issue-20261003-193301-115-integrate-validated-hotspot-agentless-backend-as-a  file: issue-20261003-193301-115-integrate-validated-hotspot-agentless-backend-as-a.md -->
