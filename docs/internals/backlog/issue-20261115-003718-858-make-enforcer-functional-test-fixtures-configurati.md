---
title: "Make enforcer functional-test fixtures configuration-cache safe and offline"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules: [enforcer, buildSrc]
target_files:
  - "enforcer/build.gradle.kts"
  - "buildSrc/src/test/java/io/mazewall/build/RootVerificationFunctionalTest.java"
  - "buildSrc/build.gradle.kts"
  - "buildSrc/src/main/java/io/mazewall/build/ManagedFixtureClasspathArgumentProvider.java"
target_symbols:
  - "test"
  - "RootVerificationFunctionalTest"
  - "ManagedFixtureClasspathArgumentProvider"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make enforcer functional-test fixtures configuration-cache safe and offline

**Context:**
PR 567 CI exposed two build-fixture failures. The `:enforcer:test` configuration cache could not serialize captured Gradle `Project`/script objects. Separately, the offline `RootVerificationFunctionalTest` fixture could not provision the Detekt plugin from its declared test setup. Keep changes within the Gradle/test fixture boundary; no production behavior change is intended. The separate `dependencyCheckUpdate` NVD HTTP 503 is an upstream database-service failure, not a vulnerability finding. It produced no scan result; preserve the security gate and do not bypass or weaken it. This issue is related to PR 567 and the paused Tier E handoff, whose durable state is summarized in [`tier-e-pause-handoff-20261114.md`](../evidence/tier-e-pause-handoff-20261114.md).

**Needed:**
1. Remove non-serializable Gradle `Project`/script captures from enforcer test configuration and demonstrate a real `:enforcer:test` configuration-cache store followed by a cache-reusing replay.
2. Keep the exact seven-JAR root-verification fixture working with configuration cache, and make its plugin provisioning self-contained for offline execution.
3. Add or repair a genuinely cold offline plugin-provisioning fixture that runs real dependency/plugin analysis, rather than only checking plugin presence or relying on a warm cache.
4. Run focused fixture and configuration-cache checks, then one full CI-equivalent quality gate. Preserve normal security scanning and report upstream NVD availability separately if it fails again.

The approved `ManagedFixtureClasspathArgumentProvider` is the only additional helper currently in scope. Any further helper or test file requires an issue target update before that file is changed. Keep the issue open until the listed verification succeeds.

---
<!-- id: issue-20261115-003718-858-make-enforcer-functional-test-fixtures-configurati  file: issue-20261115-003718-858-make-enforcer-functional-test-fixtures-configurati.md -->

## Focused verification update — 2026-11-15

The bounded local checks passed: `:enforcer:test --rerun` stored and reused the configuration cache across two invocations, with 16 selected tests and no skips/failures/errors. The offline build-logic GREEN002 fixture also exited successfully with real Java/Kotlin, Detekt, SpotBugs, coverage, and triage controls. Its plugin IDs were versionless and a separate read-only dependency cache was present, so this does not close the cold artifact-closure requirement or prove normal versioned alias resolution. See [the durable focused evidence](../evidence/pr567-ci-fixture-corrections-20261115.md).

**Still open:** run the fixture from a genuinely cold offline TestKit state with all currently required analysis, JUnit, and JaCoCo artifacts provisioned; run the one full quality gate; then review fresh PR CI. The earlier PR 567 security job failed during NVD database update with HTTP 503 before scanning, so it yielded no vulnerability result and must be rerun normally. Preserve the security gate. This issue remains OPEN until these checks are complete.
