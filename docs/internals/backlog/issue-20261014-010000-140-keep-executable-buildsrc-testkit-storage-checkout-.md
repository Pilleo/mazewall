---
title: "Keep executable buildSrc TestKit storage checkout-owned"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "testing"
target_modules: [":platform", ":enforcer", ":profiler"]
target_files:
  - "buildSrc/build.gradle.kts"
target_symbols:
  - "verificationActionsTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Keep executable buildSrc TestKit storage checkout-owned

**Context:**
PR531 baseline verification reproduced GradleWorkerMain ClassNotFoundException: the nested worker argv referenced /tmp/.gradle-test-kit/caches/9.6.1/workerMain/gradle-worker.jar, but only workerMain.lock remained. JavaExec fixtures used the shared default TestKit directory. The required gate now runs using owning buildSrc/build/test-kit without changing global caches or excluding tests.

**Needed:**
1. Bind JavaExec and JUnit TestKit storage to the owning buildSrc build directory and verify the complete real contract and convention test gates.

---
<!-- id: issue-20261014-010000-140-keep-executable-buildsrc-testkit-storage-checkout-  file: issue-20261014-010000-140-keep-executable-buildsrc-testkit-storage-checkout-.md -->

**Scoped verification:** `./gradlew -p buildSrc check` passed on the PR531 resolution checkout, including the complete executable Java contract gate and three Kotlin convention/lifecycle JUnit tests. The nested worker JAR is generated under the owning build directory; the shared `/tmp/.gradle-test-kit` cache was not modified. Full merged-tree acceptance remains pending.
