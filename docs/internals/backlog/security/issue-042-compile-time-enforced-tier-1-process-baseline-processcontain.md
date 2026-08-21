---
title: Compile-Time Enforced Tier 1 Process Baseline (`ProcessContainmentToken`)
severity: ENHANCEMENT
status: deferred
priority: low
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt
target_modules:
- :enforcer
component: enforcer
effort: medium
---

# ⚪ [Severity: ENHANCEMENT, deferred]: Compile-Time Enforced Tier 1 Process Baseline (`ProcessContainmentToken`)

**Do not implement.** `priority: low` and `deferred`. A phantom token cannot prove the kernel filter is still installed, is unusable from Java without awkward `token` plumbing, and fights tests that only need thread-scoped containment. Docs already say install Tier 1 first.

**Target:** `io.mazewall.enforcer.ContainedExecutors`
**Context:** `mazewall`'s Threat Model explicitly states that Tier 1 (process-wide `NO_EXEC` baseline) is an absolute architectural backstop against Arbitrary Code Execution (ACE) thread-hopping escapes. If a developer creates a Tier 2 (thread-scoped) sandbox without installing Tier 1, the system is highly vulnerable.
**Needed:** Not scheduled. If revisited: `installOnProcess()` would return a `ProcessContainmentToken<Tier1>` and `wrap()` would require it. That still does not prove the filter remains on the process.
