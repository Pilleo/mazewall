---
document_type: execution_plan
base_revision: b2c9b335720fd0491a8d86917a3b0593b818a4ec
status: proposed_requires_operator_approval
---

# Build-Logic Verification Optimization Implementation Plan

> **For agentic workers:** After operator approval, use `executing-plans` to implement task by task. Use `subagent-driven-development` only if the operator explicitly selects delegation. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** Proposed; this document does not authorize implementation, commits, worktrees, CI runs, or publishing.

**Goal:** Remove exhaustive build-system self-tests from routine product builds, while retaining artifact verification, native-test safeguards, and a fail-closed full verification path.

**Architecture:** Add one small smoke mode to the existing verifier. Normal product gates run it; explicit `buildLogicCheck`, `mergeCheck`, and the existing Build Logic CI job retain exhaustive coverage. Scope expensive CI steps to relevant PR changes, but run them on every default-branch push before publication.

**Tech stack:** Existing Gradle 9.8.2, Java/Kotlin, JDK 25, Gradle TestKit, GitHub Actions, and already-used `dorny/paths-filter@v3`. No new dependency or test framework.

**Spec:** Operator-approved direction: keep small safety checks by default; separate exhaustive infrastructure regressions. Update the current lifecycle contracts in [testing-strategy.md](../internals/designs/core/testing-strategy.md) and [CONTRIBUTING.md](../../CONTRIBUTING.md) as part of implementation, not as an unannounced waiver of their current requirements.

## Constraints

- Preserve strict dependency verification, locks, both root/buildSrc checksum files, and the verified Kotlin 2.4.21 additions. Do not regenerate or broaden trust as part of this optimization.
- Preserve all product lint, static-analysis, coverage, unit, kernel, fresh-JVM, and packaged/native acceptance requirements. No threshold reductions or cached native-success substitutions.
- Preserve the existing publication join: every prerequisite job must have literal `success`; failed, cancelled, skipped, and missing job results remain blocking.
- Preserve the full-suite resource probe, one-worker/no-parallel bounds, known >=2147483648-byte headroom requirement, zero additional OOM kills, and diagnostics. Keep consumer regressions outside the probe's outer process-lifetime owner.
- Do not use `-x`, `continue-on-error`, an opt-out property, blanket `onlyIf`, or a shared smoke/full success marker.
- Reuse and simplify existing fixtures. Do not build a test scheduler, custom YAML parser, new cache layer, or benchmark framework.
- Preserve unrelated uncommitted work. Implementation must enter the scoped backlog/orchestrator review path; this plan does not approve a start or merge.

## Evidence and design choice

Current observations, not a completed performance benchmark:

- `build.gradle.kts:893–913` attaches the full verifier to root `check` and puts every other selected task after it.
- `build.gradle.kts:943–945` makes `build` depend on `mergeCheck`. Removing only `check -> buildLogicCheck` would not suffice if the full suite remains in `mergeCheck`.
- `build.gradle.kts:342` also attaches expensive `consumerBuildLogicTest` to root `check`.
- An independent `build-logic-validation` job already exists in `.github/workflows/ci.yml:299–339`. Reuse it; do not introduce another job hierarchy.
- `verificationActionsTest` combines small direct assertions with many nested Gradle fixtures. A catalog change invalidates the combined execution.
- `CiCachePolicyTest.verifyBuildLogicLane()` executes 625 shell processes for four independent job-result inputs, while optional/Go checks separately test the same final join.
- The default-locale run failed after about three minutes on Unicode subprocess input. A UTF-8 retry progressed further but exceeded ten minutes. Neither is a successful full-suite timing.

Chosen approach: split smoke/full selection and scope existing CI steps. Cache-only tuning leaves unrelated work coupled; deleting the suite loses real protections against skipped tests and stale outputs. Neither is the first move.

Use full runs on default-branch pushes as the backstop. Do not add a scheduled trigger to the whole CI workflow: that would also rerun unrelated native, security, and optional-integration jobs. A separate periodic tooling workflow is deferred unless later measurements justify it.

## Intended command contract

| Command | Required behavior |
| --- | --- |
| `help`, compilation, `assemble`, buildSrc `jar` | No smoke or exhaustive verifier selected implicitly |
| `:buildLogicSmokeCheck` | Small direct safety/contract assertions; no TestKit or nested Gradle fixtures inside its executable |
| `:unitCheck` | Existing host product gates plus smoke; no exhaustive tooling or build-logic-tagged consumer fixtures |
| `:check` | Existing root checks and fast consumer harness plus smoke; no exhaustive tooling/consumer fixtures |
| `:build` / ordinary `build` | Existing product completion coverage, including kernel/fresh-JVM gates and reproducibility, plus smoke |
| `:buildLogicCheck` | Existing complete buildSrc regression suite, including the shared smoke assertions once |
| `:consumerBuildLogicTest` | Existing expensive consumer fixtures; unchanged assertions and process ownership |
| `:mergeCheck` | Product completion gates plus full `buildLogicCheck` and `consumerBuildLogicTest` |
| `./gradlew -p buildSrc test` / `check` | Still require the full buildSrc regression suite |

The tracked pre-commit hook keeps invoking `build`; it benefits from the new product-build contract without weakening its staged security audit or portal checks. Local publication guidance must require `mergeCheck`, not imply that `build` alone includes infrastructure qualification.

## Task 1: Extract a smoke execution without deleting coverage

**Files:**
- Modify `buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java`.
- Modify `buildSrc/build.gradle.kts`.
- Extend `buildSrc/src/test/java/io/mazewall/build/VerificationWorkflowInputFunctionalTest.java` for separate marker/input behavior.

**Interface:** Existing `<success-marker>` means full verification and `<success-marker> --failure-triage` keeps its current meaning. Add `<success-marker> --smoke`, selected by the new buildSrc `verificationSmokeTest` JavaExec task.

- [ ] First extend the existing verifier-registration fixture to invoke the new smoke task with the real executable and assert its distinct marker and absence of full-suite fixture output. Run that fixture red against the current registration before adding the mode. Extend it with input invalidation and failed-rerun controls described below; do not merely assert that the new task name exists.
- [ ] Extract the current normalization assertions into `verifiesDetektNormalization()` and collect existing small assertions in one method:

  ```java
  private static void verifySmoke() {
      verifiesDetektNormalization();
      verifiesFreshJvmClassMetadata();
      verifiesBacklogContracts();
      verifiesTestHealthAggregation();
      verifiesFailureTriageWaits();
  }
  ```

  Keep the current test bodies and rejection cases. Fresh-JVM metadata tests compile their tiny classes through the in-process JDK compiler; they must not launch Gradle or install containment.

- [ ] Use a private closed enum `Scope { FULL, SMOKE, FAILURE_TRIAGE }` and exhaustive switch to route execution. Reject missing/blank markers, extra arguments, and unknown switches. Smoke returns without reaching `TestKitCacheFunctionalTest.verify()` or any other nested-build fixture. Full calls `verifySmoke()` once, then all existing expensive checks; failure-triage retains its current checks.
- [ ] Register `verificationSmokeTest` with the existing executable/classpath/source-set pattern, `--smoke`, and `build/verification/verification-smoke.success`. Full retains `verification-actions.success`. Both delete only their own marker before execution and publish success only after their assertions finish. Preserve the existing full task-registration slice consumed by the workflow-input fixture.
- [ ] Declare the smoke task's source/classpath/build-registration inputs using the current Gradle conventions. Keep all existing full-suite workflow, catalog, settings, source, resource, and script inputs. Do not narrow full inputs to manufacture cache hits.
- [ ] Extend the existing input fixture with executable assertions: unchanged smoke can be up-to-date; changed relevant source reruns it; failure removes its marker; a smoke marker cannot satisfy full verification or vice versa. Use its existing small verifier substitute, not another exhaustive nested suite.

**Focused verification:**

```bash
LC_ALL=C.UTF-8 ./gradlew --no-daemon -p buildSrc verificationSmokeTest
```

Expected: small checks pass and only the smoke marker is produced by that invocation. Inspect output/process evidence for absence of TestKit fixture runs. Defer the unchanged full suite to the final checkpoint rather than replaying it after every extraction.

## Task 2: Separate product completion from exhaustive tooling qualification

**Files:**
- Modify `build.gradle.kts` at the consumer tasks, `unitCheck`, explicit gate block, `mergeCheck`, and root `build` wiring.
- Update `buildSrc/src/test/java/io/mazewall/build/BuildLogicGateFunctionalTest.java`.
- Update connected `CoreLaneFunctionalTest.java`, `AggregateVerificationFunctionalTest.java`, and `RootVerificationFunctionalTest.java` only where their copied declarations/assertions change.
- Update lifecycle guidance in `CONTRIBUTING.md`, `AGENTS.md`, and `docs/internals/designs/core/testing-strategy.md`; preserve user-owned edits.

**Interface:** `buildLogicCheck` remains exhaustive and callable. Add root `buildLogicSmokeCheck`, using the existing bounded GradleBuild bridge to buildSrc `verificationSmokeTest` with a distinct nested build name.

- [ ] First change the existing gate fixture to assert the command table above; run it red against current wiring. Replace the obsolete direct-ordering-anchor tests, rather than keeping tests of intentionally removed behavior.
- [ ] Rewire the existing lifecycle declarations along these lines, retaining their existing group/description configuration:

  ```kotlin
  tasks.named("check") {
      dependsOn("buildLogicSmokeCheck", "consumerHarnessTest")
  }
  // Add "buildLogicSmokeCheck" to unitCheck's existing selected-task allowlist.

  tasks.named("build") {
      dependsOn("unitCheck", "kernelCheck", "verifyGradleReproducibility")
  }
  tasks.named("mergeCheck") {
      dependsOn("check", "unitCheck", "kernelCheck", "verifyGradleReproducibility")
      dependsOn("buildLogicCheck", "consumerBuildLogicTest")
  }
  ```

  Remove the old `check -> buildLogicCheck`, `check -> consumerBuildLogicTest`, and `build -> mergeCheck` edges. Root `build` retains its ordinary lifecycle `check` dependency; explicitly verify that edge in the fixture. Do not drop any module/product task previously reached through `mergeCheck`.
- [ ] Remove the project-wide `mustRunAfter(":buildLogicCheck")` loop. Keep serialization inside the full bridge and existing resource/consumer supervision boundaries; do not replace the loop with another global ordering scheme.
- [ ] Use real fixture task executions to prove: product gates still execute; ordinary builds never execute exhaustive fixtures; explicit merge/full failures are blocking; smoke failures block normal builds; success markers cannot survive failed reruns; unchanged execution preserves strict configuration-cache reuse. Distinguish task outcomes/counts from console text.
- [ ] Update docs alongside this behavior change. Keep `./gradlew build` as the product completion checkpoint, document `./gradlew mergeCheck` for build-tool changes/pre-publication, and keep focused/module checks distinct from both. Do not modify the pre-commit hook's audit or arguments.

**Focused verification:**

```bash
LC_ALL=C.UTF-8 ./gradlew --no-daemon -p buildSrc buildLogicGateTest coreLaneTest
LC_ALL=C.UTF-8 ./gradlew --no-daemon :buildLogicSmokeCheck --configuration-cache --configuration-cache-problems=fail
LC_ALL=C.UTF-8 ./gradlew --no-daemon :buildLogicSmokeCheck --configuration-cache --configuration-cache-problems=fail
```

Expected: selected gate fixtures pass; the second smoke invocation reuses configuration and is up-to-date. Confirm actual `:build` product-task selection with the revised fixture and a dry run before the final real build.

## Task 3: Scope the existing CI job and simplify its policy tests

**Files:**
- Modify `.github/workflows/ci.yml`, only the `build-logic-validation` job.
- Modify `buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt`, specifically `hasBuildLogicValidation` and its explanation.
- Modify `buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt`.
- Extend existing `CoreLaneFunctionalTest.java` and `src/consumerVerification/kotlin/io/mazewall/verification/CiEfficiencyTest.kt` where actual workflow execution is tested.
- Update the CI portion of `docs/internals/designs/core/testing-strategy.md`.

**Interface:** Job ID/name and the publication join remain unchanged. Only successful irrelevant-PR classification permits an explicit no-op; job-level skipping is not permitted.

- [ ] Add `pull-requests: read` and reuse `dorny/paths-filter@v3` in a PR-only step with ID `build_logic_changes` and filter name `tooling`. Use this conservative initial input set, covering both verifier inputs and heavy consumer fixtures:

  ```yaml
  tooling:
      - 'buildSrc/**'
      - '**/*.gradle'
      - '**/*.gradle.kts'
      - '**/gradle.properties'
      - '**/*gradle.lockfile'
      - '**/gradle/verification-metadata.xml'
      - 'gradle/**'
      - 'gradlew*'
      - 'config/**'
      - 'scripts/**'
      - '.github/**'
      - 'infra/**'
      - '**/Containerfile'
      - 'src/consumerVerification/**'
      - 'src/sharedTest/**'
      - 'tests/consumers/**'
      - 'profiler/src/main/kotlin/io/mazewall/profiler/triage/**'
      - 'profiler/src/main/c/btf/**'
  ```

  Use normal action behavior for additions, modifications, and deletions. Review renamed-input behavior against the actual action; a relevant deletion must never silently omit validation. Do not substitute Python glob matching for the action's picomatch semantics.

- [ ] Apply this same condition to JDK/Gradle setup and both existing expensive execution steps:

  ```yaml
  if: github.event_name != 'pull_request' || steps.build_logic_changes.outputs.tooling != 'false'
  ```

  The only skip decision is literal `false` on a PR. Empty/malformed output selects the full run; action failure fails the job. Keep default implicit `success()` behavior so earlier failures are not erased. Retain the current commands, probe limits, `pipefail`, separate consumer step, and always-uploaded diagnostics.

- [ ] Add a small explanatory no-op step only for a successful PR filter with literal `false`. Report that exhaustive tooling checks were **not required**, not that they ran or passed. Never change `build-test-publish` to accept `skipped`, empty, failure, or cancellation.
- [ ] Replace `hasBuildLogicValidation`'s whole-job string snapshot with checks over the existing job/header/step extraction helpers. Require the safe input inventory, conditional, full commands, critical flags, independent job, bounded permissions/resources, diagnostics, and absence of error suppression. Require the filter before conditional setup/execution and the probed full command before independent consumer execution. Keep runner/JDK/action versions and resource policy unchanged. Permit comments outside command bodies and unrelated step placement while retaining the critical step names used by existing extraction helpers; do not write a general YAML parser or weaken other security/cache validators. Synchronize existing embedded valid-workflow fixtures with this contract.
- [ ] Add red/green cases for tooling PR, ordinary product-only PR, docs-only PR, default push, missing/malformed filter output, filter failure, cancelled job, and full-suite failure. Keep mutations for missing required paths, unconditional omission, `continue-on-error`, removed consumer execution, weakened strict-cache/resource flags, and omitted diagnostics. Use hosted execution to confirm GitHub behavior; local shell substitution is not a complete Actions interpreter.
- [ ] Consolidate the duplicated publication-join execution tests into one method covering all six prerequisite jobs. Execute one all-success case, four non-success cases per job, and two mixed-failure cases: **27 actual shell executions instead of the 625-case projection plus duplicate optional/Go loops**. Keep tests that remove each required `needs` entry/result test and a mutation changing an `&&` to `||`. This reduction relies on the production contract remaining a conjunction of independent literal-success tests. Preserve Go workload failure propagation and security snapshot/integrity tests unchanged.

**Focused verification:**

```bash
LC_ALL=C.UTF-8 ./gradlew --no-daemon -p buildSrc ciCachePolicyTest coreLaneTest
LC_ALL=C.UTF-8 ./gradlew --no-daemon :checkCiCachePolicy :consumerBuildLogicTest --tests io.mazewall.verification.CiEfficiencyTest
```

Expected: production workflow validator and focused fixtures pass; all non-success job results still block publication. Actual hosted PR/push selection evidence is required before claiming the CI optimization is qualified; do not open/push PRs or dispatch workflows without operator/orchestrator authorization.

## Completion checkpoint and acceptance

- [ ] Compare Task 1's smoke methods and the full entrypoint against the original: every existing functional assertion remains in the full suite, and shared smoke checks execute once in each smoke/full execution. Preserve the narrower failure-triage mode. No new general-purpose test runner or permanent timing instrumentation.
- [ ] Perform one scoped connected-code review of lifecycle edges, marker/input isolation, CI filter coverage, failure propagation, and native-test selection. Reuse the focused evidence for the same revision; do not repeat the complete suite because multiple workflow skills request it.
- [ ] Run the real product quality gate once in the configured host/container environment:

  ```bash
  LC_ALL=C.UTF-8 ./gradlew --no-daemon build
  ```

  Ordinary builds must retain all existing product/kernel checks but omit full buildSrc and build-logic-tagged consumer fixtures. Report unrelated failures without expanding this task into profiler/application repairs.

- [ ] Run the existing full Build Logic CI commands once against the final revision: the resource-probed `:buildLogicCheck`, then the independent `:consumerBuildLogicTest`. Preserve their exact supervision/strict-cache/budget flags from the workflow. Run locally only with the documented probe prerequisites; otherwise leave hosted resource qualification explicitly pending. A timeout or cache-only run is not full-suite/resource evidence.
- [ ] Record warm smoke action time, warm complete smoke-command time, expensive fixture executions selected by an ordinary build, and hosted job time/selection in the implementation review. Target smoke actions <=5 seconds and warm command <=30 seconds on the same machine with dependencies cached; these are optimization targets, not new flaky CI timeouts. Ordinary builds must select **zero exhaustive infrastructure fixture executions**. Do not promise a percentage speedup from the incomplete baseline.
- [ ] Use an available UTF-8 locale for subprocess fixtures (`LC_ALL=C.UTF-8` worked in this checkout). Do not change the user's persistent locale or turn encoding failures into accepted test outcomes. General locale-portability hardening is outside this change.
- [ ] Check documentation references, run `git diff --check` and `./scripts/adkw check-backlog`, and attach the measured results to the scoped implementation review. Do not archive an issue or claim hosted success without that evidence.

## Explicitly deferred

No sandbox/API/protocol changes; no optional-release dependency redesign; no heap/worker increases; no parallelizing owned process fixtures; no checksum synchronization framework; no further cache engineering; no mass test migration or broad CI parser rewrite. Preserve unrelated backlog work, including optional-integration release decoupling.

## Documentation sources consulted

- [Gradle 9.8.2 task execution and ordering](https://docs.gradle.org/9.8.2/userguide/controlling_task_execution.html) is the version-specific authority. Context7 had Gradle **9.6.1**, not 9.8.2, so indexed guidance was cross-checked against the wrapper's official version.
- [GitHub Actions expression/status semantics](https://docs.github.com/en/actions/reference/workflows-and-actions/expressions) (unversioned service documentation).
- [`dorny/paths-filter` v3 documentation](https://github.com/dorny/paths-filter/blob/v3/README.md), including PR permissions, literal boolean outputs, and picomatch rules.
