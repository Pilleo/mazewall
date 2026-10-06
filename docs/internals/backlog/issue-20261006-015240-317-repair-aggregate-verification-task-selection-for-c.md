---
title: "Repair aggregate verification task selection for container projects"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
   - "build.gradle.kts"
   - "buildSrc/build.gradle"
   - "buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java"
   - "buildSrc/src/test/java/io/mazewall/build/AggregateVerificationFunctionalTest.java"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Repair aggregate verification task selection for container projects

**Context:**
Approved inline execution on fast-master is blocked by the reproduced unitCheck dependency failure: the plugin-free :portals container is required to provide unitCheck. Root kernelCheck has the same selector defect; new integration grouping projects will also trigger it.

**Needed:**
1. Execute the actual root aggregation declarations in a Gradle fixture with grouping projects and Java child modules, reproduce failure, and lazily depend only on actual task providers while retaining all child quality gates.

**Implementation evidence:**

- Root `unitCheck` and `kernelCheck` depend on lazy matching task collections rather than invented task paths for every non-demo project.
- The executable TestKit fixture runs the production root declarations against plugin-free grouping projects, Java children, a custom unit-only provider, and excluded demo projects. Both the initial and configuration-cache-reused graphs execute every required child gate.
- Red: `./gradlew -p buildSrc verificationActionsTest` failed because `:group:unitCheck` did not exist.
- Green: the same verification command passed after changing root task selection.
- Full host baseline: `./gradlew unitCheck` passed, exit 0; 121 actionable tasks, 6 executed and 115 up-to-date.
- Exact fixture task paths are `:unitCheck` and `:kernelCheck`; bare Gradle task selectors independently select matching child tasks as well.
- No kernel enforcement or coverage requirement was removed. The issue remains open pending the repository review/resolution path.

---
<!-- id: issue-20261006-015240-317-repair-aggregate-verification-task-selection-for-c  file: issue-20261006-015240-317-repair-aggregate-verification-task-selection-for-c.md -->
