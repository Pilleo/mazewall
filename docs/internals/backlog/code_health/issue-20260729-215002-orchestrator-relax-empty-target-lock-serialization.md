---
title: "Relax Scheduler Serialization to Prevent Empty Target Lists from Acting as Global Blocking Locks"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Relax Scheduler Serialization to Prevent Empty Target Lists from Acting as Global Blocking Locks

**Context:**
The Orchestrator's scheduler is designed to schedule multiple tasks concurrently, provided they are disjoint and do not conflict.
However, if a task has an empty `target_files` or `target_modules` list, it is scheduled conservatively:
```kotlin
            val conflictFreeIssues = unblockedIssues.filter { issue ->
                if (activeIssues.isEmpty()) {
                    true
                } else {
                    issue.targetFiles.isNotEmpty() && issue.targetModules.isNotEmpty() &&
                    activeIssues.none { active ->
                        active.targetFiles.isEmpty() || active.targetModules.isEmpty() ||
                        issue.targetFiles.any { it in active.targetFiles } ||
                        issue.targetModules.any { it in active.targetModules }
                    }
                }
            }
```
This design is extremely restrictive in practice:
1. If there is ANY active task that has empty `target_files` or empty `target_modules` (such as a review task, documentation update, or high-level audit task), it acts as a global execution lock/barrier. It blocks ALL other candidate tasks from starting in parallel, completely serializing execution even if those tasks are completely independent and touch unrelated modules (e.g., a profiler test vs. an enforcer fix).
2. If a candidate task has empty targets itself, it can never be scheduled in parallel with any active task.

This serialization severely degrades execution throughput and causes the scheduler to under-utilize parallel capacity, leaving unrelated, non-conflicting backlog items stuck in queue.

**Needed:**
1. Refine the parallel scheduler's conflict check to be more granular. Instead of treating empty lists as an absolute conflict with everything, classify tasks based on whether they actually require exclusive/isolated execution or if they are non-interfering.
2. For example, introduce a specific metadata tag or fallback category (e.g. `exclusive: true` or checking the component type).
3. Alternatively, if a task has empty target modules/files but is a pure review/documentation/audit task (e.g., matching component `"docs"` or `"ci"` or contains `"review-task"` in its ID), allow it to run concurrently with other module-specific tasks, since it has zero chance of conflicting with Gradle compilation or git modifications in `:enforcer` or `:profiler`.
4. Implement corresponding tests in `ParallelTaskSchedulerTest.kt` verifying that non-interfering or component-specific empty-target tasks can be scheduled alongside active, module-specific tasks without causing global serialization.
