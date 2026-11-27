---
title: "Keep executable buildSrc TestKit storage checkout-owned"
severity: "MEDIUM"
status: "resolved"
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
## Repository-review expansion — 2026-11-04

[BUILD-04](../reviews/2026-11-04-repository-review-findings.md#build-04): root buildLogicCheck
reaches the Java verifier but not the three existing Kotlin convention/lifecycle JUnit cases.
The dependency is test→verificationActionsTest, not the reverse; bootstrap intentionally does
not fill the gap. Make the explicit exhaustive bridge reach all current tests once with bounded
workers and bootstrap isolation. A deliberately failing copied Kotlin case must fail the bridge;
record all three observations. Current build reused the Java verifier, not fresh exhaustive proof.

---
<!-- id: issue-20261014-010000-140-keep-executable-buildsrc-testkit-storage-checkout-  file: issue-20261014-010000-140-keep-executable-buildsrc-testkit-storage-checkout-.md -->

**Scoped verification:** `./gradlew -p buildSrc check` passed on the PR531 resolution checkout, including the complete executable Java contract gate and three Kotlin convention/lifecycle JUnit tests. The nested worker JAR is generated under the owning build directory; the shared `/tmp/.gradle-test-kit` cache was not modified. Full merged-tree acceptance remains pending.

## Root gate verification on 2026-11-25

The root bridge selects buildSrc `test`. Its existing dependency on
`verificationActionsTest` runs the Java verifier and Kotlin JUnit suite in one
nested invocation. Both suites use the configured Gradle User Home for TestKit
under the later [cache ownership and reuse work](../issue-20261019-203415-734-reuse-nested-gradle-testkit-caches-across-disposab.md).

- The focused `:buildLogicGateTest` fixture failed against the original bridge
  because it omitted the nested test. With the fix, the fixture passed its
  execution-count, JUnit failure, recovery, bootstrap, ordering, and strict
  configuration-cache reuse checks.
- The CI build-logic command completed in 12m 48s. The Java verifier wrote its
  `verified` marker and JUnit reported three Kotlin tests with zero failures,
  errors, or skips.
- The same command failed in a disposable source copy with a deliberately
  incorrect `BuildConventionsFunctionalTest` assertion. The Java verifier
  completed, the Kotlin suite reported the one intended failure, and the root
  gate exited with status 1.
- `./gradlew build` exited 0. The baseline build passed in 9m 15s and reused the
  verified build-logic results. The confined Gradle run passed in 3m 31s and its
  receipt recorded 42 tests with zero failures, errors, or skips.
