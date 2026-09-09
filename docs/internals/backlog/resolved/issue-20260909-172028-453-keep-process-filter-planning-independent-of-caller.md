---
title: "Keep process filter planning independent of caller thread restrictions"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
target_symbols:
  - "ContainedExecutors"
open_questions: false
---

# Resolved: Keep process filter planning independent of caller thread restrictions

**Context:**
A thread-scoped policy incorrectly satisfies process-wide planning, skipping TSYNC for peer threads.

**Needed:**
1. Use process state for process installation and prove thread-then-process behavior.

**Resolution evidence (2026-09-09):** Both fast and locked process-wide planners use
process state; thread planning, depth checks, and kernel self-verification retain
the combined effective state. `PermanentInstallationTest` performs thread-then-process
installation in an isolated JVM and asserts an additional native TSYNC call and
process filter depth. Full enforcer tests passed; containment documentation and
PlantUML/SVG diagrams updated in architecture repair Task 3.

---
<!-- id: issue-20260909-172028-453-keep-process-filter-planning-independent-of-caller  file: issue-20260909-172028-453-keep-process-filter-planning-independent-of-caller.md -->
