---
title: "Root verification graph must select grouping Java and Kotlin quality gates by plugin"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["buildSrc"]
target_files: ["build.gradle.kts", "buildSrc/src/test/java/io/mazewall/build/RootVerificationFunctionalTest.java"]
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Root verification graph must select grouping Java and Kotlin quality gates by plugin

**Context:**
The actual optional combined Koog/Spring build failed while resolving
`:integrations:check`: root quality wiring required `detektMain` on implicit
grouping projects and Java-only leaves. Applying Java and Detekt unconditionally
fabricated source-module tasks and broke the selected production build graph.

Task 11 owns only root verification wiring, its executable buildSrc fixture and
testing guidance. Source modules must retain their real checks: Java tests,
SpotBugs and coverage; Kotlin typed Detekt; lazy unit/kernel aggregates; native
freshness. Compiler and dependency baselines are unchanged.

**Needed:**
1. Capture the actual missing-task graph failure and an executable TestKit red.
2. Admit Java quality by actual Java plugin and typed Detekt by Kotlin JVM plugin;
   use lazy child lifecycle task collections for grouping projects.
3. Execute real Java/Kotlin analysis and Java tests in the production-convention
   fixture; prove injected test/SpotBugs/Detekt failures remain failures.
4. Verify actual core/optional graphs and one combined bootstrap build dry-run.
5. Keep this issue open through independent review and coordinator integration.

**Evidence:** task-11 checkpoint/report in the shared integration foundation plan.
Actual graph red `run-d8ce92cb9717`; executable red `run-4adb0b64b70b`;
first complete fixture green `run-299195470d2b`. Expected analyzer/test mutation
failures are asserted by the fixture; they are not ignored production failures.

**Separate coordinator follow-up:** focused `bash scripts/check_gradle_lazy_resolution.sh`
still rejects Spring build line 34. The `.asPath` expression is inside the
execution-time `doFirst` action at lines 32–35; the line-based scanner cannot
recognize that enclosing action. This is a scanner false positive, not eager
Spring classpath resolution. Tooling and adapter edits are outside task 11;
retain the gate and resolve its structural/action-context detection separately
before claiming a complete combined build.

---
<!-- id: issue-20261008-104505-709-root-verification-graph-must-select-grouping-java-  file: issue-20261008-104505-709-root-verification-graph-must-select-grouping-java-.md -->
