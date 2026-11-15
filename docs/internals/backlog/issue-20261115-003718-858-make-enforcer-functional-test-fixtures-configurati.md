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
  - "buildSrc/src/test/java/io/mazewall/build/VerificationWorkflowInputFunctionalTest.java"
target_symbols:
  - "test"
  - "RootVerificationFunctionalTest"
  - "ManagedFixtureClasspathArgumentProvider"
  - "VerificationWorkflowInputFunctionalTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make enforcer functional-test fixtures configuration-cache safe and offline

**Context:**
PR 567 CI exposed two build-fixture failures. The `:enforcer:test` configuration cache could not serialize captured Gradle `Project`/script objects. Separately, the offline `RootVerificationFunctionalTest` fixture could not provision the Detekt plugin from its declared test setup. Keep changes within the Gradle/test fixture boundary; no production behavior change is intended. The separate `dependencyCheckUpdate` NVD HTTP 503 is an upstream database-service failure, not a vulnerability finding. It produced no scan result; preserve the security gate and do not bypass or weaken it. This issue is related to PR 567 and the paused Tier E handoff, whose durable state is summarized in [`tier-e-pause-handoff-20261114.md`](../evidence/tier-e-pause-handoff-20261114.md).

**Needed:**
1. Remove non-serializable Gradle `Project`/script captures from enforcer test configuration and demonstrate a real `:enforcer:test` configuration-cache store followed by a cache-reusing replay.
2. Keep the exact seven-JAR root-verification fixture working with configuration cache, and make its plugin provisioning self-contained for offline execution.
3. Add or repair a genuinely cold offline plugin-provisioning fixture that runs real dependency/plugin analysis, rather than only checking plugin presence or relying on a warm cache. The extracted JavaExec production registration must copy the declared required `gradle/verification-metadata.xml` into the fixture; test that changing only its checksum input invalidates the task before the existing workflow cases.
4. Run focused fixture and configuration-cache checks, then one full CI-equivalent quality gate (completed locally in full-incremental proposal 002). Preserve normal security scanning and report upstream NVD availability separately if it fails again; fresh remote CI and a successful security scan remain pending.

The approved `ManagedFixtureClasspathArgumentProvider` is the only additional helper currently in scope. Any further helper or test file requires an issue target update before that file is changed. Keep the issue open until the listed verification succeeds.

---
<!-- id: issue-20261115-003718-858-make-enforcer-functional-test-fixtures-configurati  file: issue-20261115-003718-858-make-enforcer-functional-test-fixtures-configurati.md -->

## Focused verification update — 2026-11-15

The bounded local checks passed: `:enforcer:test --rerun` stored and reused the configuration cache across two invocations, with 16 selected tests and no skips/failures/errors. The offline build-logic GREEN002 fixture also exited successfully with real Java/Kotlin, Detekt, SpotBugs, coverage, and triage controls. Its plugin IDs were versionless and a separate read-only dependency cache was present, so this does not close the cold artifact-closure requirement or prove normal versioned alias resolution. See [the durable focused evidence](../evidence/pr567-ci-fixture-corrections-20261115.md).

**At the GREEN002 checkpoint, still open:** run the fixture from a genuinely cold offline TestKit state with all currently required analysis, JUnit, and JaCoCo artifacts provisioned; complete the full quality gate; then review fresh PR CI. The full quality gate was later completed locally as recorded below. The earlier PR 567 security job failed during NVD database update with HTTP 503 before scanning, so it yielded no vulnerability result and must be rerun normally. Preserve the security gate.

## Verification follow-up — 2026-11-15

Build-logic GREEN006 passed the actual workflow-input suite: the production JavaExec registration copies the unchanged `gradle/verification-metadata.xml`, and checksum-only input invalidation is tested before the existing workflow cases. The fixture used an explicit online preparation phase for 101 selected configurations, then ran real Java/Kotlin, Detekt, SpotBugs, coverage, and 14 triage cases offline in fresh synthetic projects with an empty plugin repository. It reused the same task-owned TestKit cache, so this does not prove execution from a newly empty cache without preparation. JaCoCo 0.8.14 and ktlint 1.7 were restored from repository-declared inputs. Earlier failed preparation attempts remain documented in the [focused evidence](../evidence/pr567-ci-fixture-corrections-20261115.md).

PR run 43663609264 passed the core cache prime/reuse segment but remains red: it next exposed unavailable offline Detekt CLI and its kernel lane reported 19/244 failures. The security job's NVD update again returned HTTP 503, so the vulnerability scan was skipped and no scan result exists. These are not grounds to bypass, skip, or weaken checks.

**At the GREEN006 checkpoint, still required:** a fresh remote PR run and closure of the genuinely cold/offline artifact-availability requirement; the local full quality gate is recorded in the later follow-up below. This issue remains OPEN; GREEN006 is a focused fixture result, not an all-CI-green claim.

## Verification follow-up — 2026-11-15

The local standalone failure-triage fixture passed 14 executable controls after an online preparation phase for existing dependencies; its analysis assertions ran offline and verification metadata was unchanged. This is prepared-offline evidence, not proof that a cold, empty TestKit cache can execute the complete current analysis graph.

PR run 43669767931 passed the core host/configuration-cache segment, optional checks, mutation job, and Go job. Build Logic Validation reached failure triage after its main fixture passed, then failed because offline `org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0` was unavailable. The core kernel lane reported 19 failures / 244 tests / 2 skipped. The security snapshot was restored, but repeated NVD HTTP 503 responses stopped `dependencyCheckUpdate` before scanning; no scan result exists. Preserve all checks and the security gate.

The local full quality gate passed in full-incremental proposal 002. Still required: establish the cold offline artifact closure and review a fresh remote CI run with both required ordinary and private kernel lanes passing, including an actual security scan result. This issue remains OPEN. See the [focused evidence and receipt provenance](../evidence/pr567-ci-fixture-corrections-20261115.md).

## Full local quality gate — 2026-11-15

Full-incremental proposal 001 is retained as a RED attempt whose only failure was ktlint formatting in two `enforcer` Kotlin script lines. The whitespace correction passed focused `:enforcer:ktlintKotlinScriptCheck` and `ConfinedHelperLaneFunctionalTest`; proposal 002 then passed the offline full build with configuration-cache problems set to fail. The build ran for 30m25s, completed 248 actionable tasks (135 executed, 8 from cache, 105 up-to-date), and stored the configuration cache. This cached local gate does not establish the cold artifact-closure requirement.

PR run 43669767931 remains red, with the offline failure-triage dependency gap for `org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0`, 19/244 core kernel failures and 2 skipped, and a security update blocked by NVD HTTP 503 after snapshot restoration. No scan result was produced. Keep issue 858 OPEN until cold offline artifact closure and fresh required CI succeed. Full-gate and current-run receipts are recorded in the [focused evidence](../evidence/pr567-ci-fixture-corrections-20261115.md).
