---
title: "Enforce LinuxNative coverage with the actual platform class selector"
severity: "MEDIUM"
status: "resolved"
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
**Resolution — 2026-10-12:** Needed step1 fulfilled: actual LinuxNative FQN selector, real action old-selector100% PASS / corrected100% FAIL / restored78% PASS on identical execution/classes accepted. E=`/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/`: `task-6d1/task-6d1-review.md` and coverage receipts; P617 continuation coverage/native-floors.json actual EXECUTED59/62=95.1613%, unchanged78% floor. [Acceptance](../../reviews/2026-10-12-integration-contract-completion.md).

Task6b D1 found the 78 percent LinuxNative selector names an absent old FQN. Actual class coverage is 59 of 62 instructions, but the stale selector passes even a 100 percent floor.

**Needed:**
1. Correct only the class selector; prove the actual Gradle JaCoCo action rejects a temporary 100 percent floor and passes the restored 78 percent floor with unchanged execution data and classes.

---
<!-- id: issue-20261012-143218-956-enforce-linuxnative-coverage-with-the-actual-platf  file: issue-20261012-143218-956-enforce-linuxnative-coverage-with-the-actual-platf.md -->
