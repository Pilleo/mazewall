---
title: "Multi-Issue Parallel Execution & Conflict-Free Task Scheduler"
severity: "HIGH"
status: "open"
priority: 8
dependencies: ["issue-20260726-03"]
component: "orchestrator"
target_modules: [":tools:orchestrator"]
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorContext.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
effort: "large"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Multi-Issue Parallel Execution & Conflict-Free Task Scheduler

**Context:**
The Orchestrator operates sequentially on a single issue at a time. To scale throughput, the Orchestrator needs to execute multiple non-conflicting tasks concurrently.

**Needed:**
1. Extend `OrchestratorContext` to support tracking multiple active task execution slots in parallel.
2. Implement a conflict-free task selection algorithm in `SELECT_TASK`:
   - A candidate task B can run concurrently with active tasks if `target_files(B) ∩ target_files(active) = ∅` AND `target_modules(B) ∩ target_modules(active) = ∅`.
3. Support concurrent Jules session tracking, parallel CI monitoring, and isolated state persistence per active task slot.
