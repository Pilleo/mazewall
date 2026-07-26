---
title: Implement Subsystem Domain Locking and Core File Exclusive Execution in Dependency
  Graph
severity: HIGH
status: open
priority: 10
dependencies:
- issue-20260726-191001
component: orchestrator
target_modules:
- :tools:orchestrator
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/DependencyGraph.kt
effort: medium
autonomy: autonomous
---

# 🔴 [Severity: HIGH]: Implement Subsystem Domain Locking and Core File Exclusive Execution in Dependency Graph

**Context:**
Even with non-empty `target_files`, parallel tasks modifying core bottleneck files (`Syscall.kt`, `Platform.kt`, `Policy.kt`, `AGENTS.md`) or touching files in the same subsystem package directory cause merge collisions when rebasing on `master`.

**Needed:**
1. Define a `SHARED_CORE_FILES` set in `DependencyGraph.kt` containing high-churn core interface files (`Syscall.kt`, `Platform.kt`, `Policy.kt`, `AGENTS.md`).
2. Implement exclusive single-slot scheduling for core files: if an issue's `target_files` intersects with `SHARED_CORE_FILES`, ensure no other active slots are currently modifying core files.
3. Implement subsystem package directory intersection checks (e.g. `enforcer/src/.../supervisor` vs `enforcer/src/.../bpf`).
4. Write comprehensive unit tests in `DependencyGraphConflictTest.kt` validating that domain collisions and core file locks correctly prevent concurrent task scheduling.
