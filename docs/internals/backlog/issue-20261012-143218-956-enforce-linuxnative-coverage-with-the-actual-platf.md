---
title: "Enforce LinuxNative coverage with the actual platform class selector"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "build.gradle.kts"
target_symbols:
  - "jacocoKernelCoverageVerification"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Enforce LinuxNative coverage with the actual platform class selector

**Context:**
Task6b D1 found the 78 percent LinuxNative selector names an absent old FQN. Actual class coverage is 59 of 62 instructions, but the stale selector passes even a 100 percent floor.

**Needed:**
1. Correct only the class selector; prove the actual Gradle JaCoCo action rejects a temporary 100 percent floor and passes the restored 78 percent floor with unchanged execution data and classes.

---
<!-- id: issue-20261012-143218-956-enforce-linuxnative-coverage-with-the-actual-platf  file: issue-20261012-143218-956-enforce-linuxnative-coverage-with-the-actual-platf.md -->
