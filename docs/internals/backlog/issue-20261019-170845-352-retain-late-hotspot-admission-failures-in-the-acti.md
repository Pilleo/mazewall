---
title: "Retain late HotSpot admission failures in the active command owner"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "startAgentlessHotspot"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain late HotSpot admission failures in the active command owner

**Context:**
C1 real startup capability-body proof retains a pending feature after actual 10s admission and 2s cleanup waits, then returns EACCES; main nulls its error channel on the earlier admission failure, losing live delivery. Proof is C1-review/startup-repro.log.

**Needed:**
1. Keep servicing a retained feature owner after admission failure; test the actual main owner-routing boundary with joined held body, immediate timeout and later original permission error without stopping baseline processing.

---
<!-- id: issue-20261019-170845-352-retain-late-hotspot-admission-failures-in-the-acti  file: issue-20261019-170845-352-retain-late-hotspot-admission-failures-in-the-acti.md -->
