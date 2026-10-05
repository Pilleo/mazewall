---
title: "Bound interpreter self-profiling asset validation and dense readout cost"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot"
target_symbols:
  - "HotSpotAssets"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bound interpreter self-profiling asset validation and dense readout cost

**Context:**
Real pinned -Xint current-JVM acceptance measured 34-82 second asset-admission cost and rejected a 600-operation 1ms window under the unchanged 2-second stop bound; snapshots preserved only 153-298 received records before abort

**Needed:**
1. Retain rejected fixture measurements and profile validation/JSON mapping cost before changing supported workload bounds or native budgets

---
<!-- id: issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-  file: issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md -->
