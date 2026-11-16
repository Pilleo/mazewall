---
title: "Preserve full kernel coverage across baseline and confined CI lanes"
severity: "HIGH"
status: "open"
priority: high
dependencies: [issue-20261115-003718-858]
component: "core"
target_modules: [enforcer, buildSrc]
target_files:
  - "enforcer/build.gradle.kts"
  - ".github/workflows/ci.yml"
  - "scripts/run_confined_helper_tests.sh"
  - "scripts/verify_confined_helper_results.py"
  - "scripts/test_confined_helper_ci.py"
  - "infra/dev/confined-helper-seccomp.json"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - "buildSrc/src/test/java/io/mazewall/build/ConfinedHelperLaneFunctionalTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java"
  - "buildSrc/build.gradle.kts"
target_symbols:
  - "integrationTest"
  - "kernelCheck"
  - "CheckCiCachePolicy"
  - "CiCachePolicyTest"
  - "ConfinedHelperLaneFunctionalTest"
  - "VerificationActionsTest"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve full kernel coverage across baseline and confined CI lanes

**Context:**
PR 567's second CI run showed that its rootless Podman execution environment cannot satisfy the existing private confined-helper checks: 19/244 failures included distinct `map_files` EPERM and `/dev/null` EXDEV results. Preserve every test in the ordinary kernel lane and execute the three affected complete classes in a separate required private lane. This is a CI execution-envelope correction, not a production sandbox change or permission to skip, suppress, or weaken tests. Relates to [issue 858](issue-20261115-003718-858-make-enforcer-functional-test-fixtures-configurati.md) and [PR 567](https://github.com/Pilleo/mazewall/pull/567); the separate NVD 503/scan-skipped event is not caused by this issue.

**Needed:**
1. Add one finite, validated integration-test selector with `ALL` as the unchanged default and explicit `BASELINE`/`CONFINED` values. Apply it only to `integrationTest` and exactly these complete classes: `io.mazewall.enforcer.api.execution.ConfinedHelperReadinessIntegrationTest`, `io.mazewall.ffi.networking.ConfinedHelperProcessIntegrationTest`, and `io.mazewall.landlock.OwnedProcFdReopenKernelTest`. Preserve fresh-JVM partitioning and fail on invalid values. Do not add or change test-source annotations.
2. Add functional regression coverage for exact lane partitions, unchanged default discovery, fresh-JVM partitioning, invalid modes, and proof that each of the three classes runs as a whole. The existing ordinary Podman invocation passes `BASELINE` without changing its profile or security envelope. A separate required job runs `CONFINED` only for `:enforcer:integrationTest --rerun`; verify fresh XML contains positive, unique per-class test counts with zero skips/failures/errors, while ordinary-lane XML contains none of these classes.
3. Make the aggregate require both ordinary `core-quality` and the private job to succeed. Test absent, failed, cancelled, and skipped private-job results. Update the build-logic CI graph regression and add the bounded lane functional test.
4. Implement and preflight the private runner with the approved exact profile bytes: Moby v0.2.4 baseline plus only the `pidfd_getfd` argument-2-equals-zero rule, SHA-256 `94b42d4064fc993773f44488ea55406017ab1ef5a0ee5ccc3e5fd19859763136`. Keep the existing Podman profile unchanged. Require proven rootful Docker and initial user namespace identity, `--userns=host`, bridge networking, read-only root, no-new-privileges, exact reviewed mounts, and actual parent/helper `map_files` plus `/dev/null` descriptor/mount identity checks. Fail preflight if existing `setpriv` is unavailable; install no dependencies. Use the four bootstrap capabilities only for `setpriv`, then transition to the caller UID/GID, clear supplementary groups, and assert all five capability masks contain exactly `CHECKPOINT_RESTORE` before Java runs. Record image, kernel, JDK, ownership, mount identities, output counts, and physical retirement.
5. The focused selection and workflow tests, local private lane, and full quality gate are recorded in the verification follow-ups below. Obtain fresh required remote CI review for both the ordinary and private lanes before resolving this issue.

No helper checks, `NO_XDEV`, security profile, default task filters, profiler obligations, production APIs, NativeEngine, dependencies, or Live guards may be weakened or expanded. If the rootful identity, mount/descriptor checks, or all three complete test classes cannot be proven in the CI runner, fail the private job and retain the limitation. This issue remains OPEN; local implementation and focused verification do not qualify the required remote ordinary and private CI lanes.

---
<!-- id: issue-20261115-024924-787-preserve-full-kernel-coverage-across-baseline-and- file: issue-20261115-024924-787-preserve-full-kernel-coverage-across-baseline-and-.md -->

## Verification follow-up — 2026-11-15

The lane-selection implementation and focused local fixtures are in place. Stage 1 proposal 002 passed the Python runner-validation suite (11 tests), actual JUnit checks for default, `ALL`, `BASELINE`, `CONFINED`, and invalid selector behavior with fresh-JVM partitioning, plus the CI-policy negative controls (4 executed, 11 up-to-date). The selector still defaults to `ALL`; `BASELINE` and `CONFINED` retain complete-class partitioning. Stage 1 proposal 001's earlier RED policy-fixture result remains preserved.

The local private runner completed all three full classes with fresh XML: readiness 8, confined-helper process 26, and proc-fd reopen 8, for 42 tests with zero skips, failures, or errors. The runner, validator, container exit, and physical retirement receipts passed. The exact approved profile digest is `94b42d4064fc993773f44488ea55406017ab1ef5a0ee5ccc3e5fd19859763136`; the ordinary Podman profile remains unchanged. The local host was kernel `7.2.11-x64v3-xanmod1`, JDK `25.0.5+8-LTS`, amd64; the workload ran as UID/GID 1000. `/dev` and `/dev/null` had device 108, and the `NO_XDEV` proc-fd test passed. This local caller identity does not qualify the CI runner's UID/GID 1001.

PR run 43669767931 remains red: the ordinary core kernel lane reported 19 failures / 244 tests / 2 skipped, and the security scan was blocked by repeated NVD HTTP 503 responses after snapshot restoration. The remote run therefore does not qualify either required lane as complete. The local full quality gate passed in full-incremental proposal 002. A fresh remote run with both ordinary and private kernel lanes passing remains pending. This issue remains OPEN; implementation and focused local verification are recorded, but full remote CI qualification is not.

See the [focused evidence and receipt provenance](../evidence/pr567-ci-fixture-corrections-20261115.md) for exact local and CI receipt hashes.

## Full local quality gate — 2026-11-15

The initial full-incremental proposal 001 failed only on ktlint formatting in two `enforcer` Kotlin script lines. The whitespace-only correction passed focused `:enforcer:ktlintKotlinScriptCheck` and `ConfinedHelperLaneFunctionalTest`. Proposal 002 then completed the offline full build with configuration-cache problems set to fail: **BUILD SUCCESSFUL** in 30m25s, 248 actionable tasks (135 executed, 8 from cache, 105 up-to-date), configuration cache stored. The scoped 15-second prewarm setting applied only to `:enforcer:integrationTestFreshJvm`; the ordinary/private selector defaults and security profile were not changed. The executed integration tasks reported 244 tests / 2 skipped / 0 failures for `integrationTest` and 120 / 1 skipped / 0 failures for `integrationTestFreshJvm`. The full run includes cached and up-to-date results, so this is not an all-fresh test count.

The local private-lane receipts remain 42 tests across the three complete classes, zero skipped/failures/errors; `/dev` and `/dev/null` shared device 108 and the `NO_XDEV` proc-fd case passed. This does not qualify the remote required private lane. PR run 43669767931 remains red, including 19/244 failures and 2 skipped in the ordinary core kernel lane, and the latest NVD HTTP 503 blocked a security scan after snapshot restoration. A fresh remote CI run satisfying both required kernel lanes and the security scan is pending; this issue remains OPEN. See the [focused evidence and full-gate receipt provenance](../evidence/pr567-ci-fixture-corrections-20261115.md).
