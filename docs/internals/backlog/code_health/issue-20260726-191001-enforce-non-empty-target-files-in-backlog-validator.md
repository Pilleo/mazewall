---
title: Enforce Mandatory target_files in Backlog Validator for Conflict-Free Parallel
  Scheduling
severity: HIGH
status: open
priority: 10
dependencies: []
component: orchestrator
target_modules:
- :tools:orchestrator
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt
effort: small
autonomy: autonomous
---

# 🔴 [Severity: HIGH]: Enforce Mandatory target_files in Backlog Validator for Conflict-Free Parallel Scheduling

**Context:**
The Orchestrator relies on `target_files` to detect file collisions and safely schedule parallel Jules agent sessions. However, backlog issue authors and automated issue generation routines frequently leave `target_files: []` empty, bypassing conflict checks and leading to git merge collisions during parallel execution.

**Needed:**
1. Update `BacklogValidator.kt` to enforce that every `open` or `in_progress` backlog issue contains a non-empty `target_files` list.
2. If `target_files` is empty or missing, `BacklogValidator` must fail schema validation with an explicit error during `./gradlew :tools:orchestrator:checkBacklog`.
3. Add unit tests in `BacklogValidatorTest.kt` verifying that issues with empty `target_files` are cleanly rejected.
