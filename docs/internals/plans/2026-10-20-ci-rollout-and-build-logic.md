---
document_type: execution_plan
base_revision: 512565a7bc08aae0466687fe5adcd2e7f76009e0
---

# CI Rollout and Explicit Build-Logic Gate Implementation Plan

> **For agentic workers:** Use `subagent-driven-development` for the implementation tasks, or `executing-plans` inline with one final independent review. Steps use checkboxes. This document is a plan, not authorization to merge, publish, start another orchestrator, or create a worktree.

**Goal:** Finish verifying the merged security repair, then eliminate repeated build-tool regression execution without losing verification or memory safety.

**Architecture:** First qualify the existing default-branch security/cache/publication lifecycle. Then selectively reuse the held explicit-gate implementation: a public GradleBuild task keeps complete local verification mandatory, while a separate required CI runner avoids executing that suite during every producer bootstrap. Preserve the merged security lane and both recent review fixes.

**Tech Stack:** Existing Gradle 9.6.1, JDK 25, Kotlin DSL, Gradle TestKit, GitHub Actions and Python resource probe. Library compatibility remains JDK 22+.

**Spec:** [Testing strategy](../designs/core/testing-strategy.md), [build baseline](../build/gradle-baseline.md), root [agent invariants](../../../AGENTS.md), and the binding scope below. The deleted superpowers documents are not prerequisites and must not be restored.

## Binding scope and current evidence

- PR550 merged as `512565a7`; default push run `39483882935` was still running when this plan was written. No default security or publication success is assumed.
- PR cold update completed in 49m29s; a security-only warm rerun took 3m20s. Corrected-head full CI took 15m38s. These are observations on different cache paths, not a controlled speedup benchmark.
- PR snapshots are merge-ref scoped and cannot seed master. NVD Last Checked remained unchanged during the warm run inside normal validity; a due refresh is still unproven.
- Held branch `perf/explicit-build-logic-gate`, tip `f8f41c52`, contains reusable code over `40321646`, but predates merged security and workflow-input/root-fixture fixes. Its earlier local verification is historical, not qualification of a new combined tree.
- Complete root `check`/`mergeCheck`/`build` must require build-tool regressions. Fast bootstrap/help/compile/assemble paths need not run them. No claim that focused checks replace the complete gate.
- The Portal retirement investigation remains open. Passing runs do not identify its failing owner or establish a fix.

## Global Constraints

- Never recreate `.superpowers/` or `docs/superpowers/`; no worktrees without separate explicit permission. Use the current checkout and preserve unrelated changes.
- No new dependencies/services, library API or native/runtime changes, heap/fork/worker increases, reduced thresholds, stale-data fallback or native-outcome caching.
- Never modify, filter, or handle `GITHUB_TOKEN`; leave operator credentials and authentication unchanged.
- Preserve CVSS7, normal scanner updates/validity/errors, missing-key/docs-only eligibility, version/OS/architecture isolation, caller ownership, completed-only snapshots and persistence-failure analysis behavior.
- Preserve `ciCachePolicyWorkflow` as a relative-path-sensitive verifier input, every other declared input/case, pre-execution marker deletion, and actual foreign-UID fixture selection. Do not claim actual root execution from modeled selection.
- Preserve both existing required check names, optional acceptance, core/native/demo/mutation checks, PR-only cancellation, forwarded container identity and default-push-only package publication.
- One local Gradle invocation at a time. Dedicated heavy CI lanes use separate runners; nested build-logic verification uses one worker and no project parallelism.
- Executed-workload resource receipts need known headroom >=2147483648 bytes and zero additional OOM kills. Unknown/unsafe evidence fails qualification; cached or UP-TO-DATE paths are not full-workload memory proof.
- Starts/merges stay in the project approval/control-plane path. Do not start another daemon, mutate orchestrator state, force-push, or merge just to populate a cache. Keep issues open until their acceptance evidence exists.

## Review Focus

1. Selective recovery must retain merged security and review fixes, not resurrect deleted folders or replace newer build/test files wholesale.
2. Complete local builds must select the verifier before heavy work; fast paths must not select it. A single-worker execution order is not a concurrent-overlap witness.
3. Reused configuration/outputs must not conceal changed workflow/settings/source inputs or retain an old success marker after failure.
4. Publication must require literal success from core, mutation, security AND build logic, plus optional acceptance; every absent/skipped/cancelled/failed result blocks it.
5. Actual default-branch cache restoration, due update, fresh analysis/publication and real executed-suite memory must be observed, not inferred from PR caches, mocks or warm markers.

## File map

| Paths | Responsibility |
|---|---|
| Existing `.github/workflows/ci.yml`, security reports/cache metadata | Tasks1/2 observe the merged rollout without changing scanner configuration |
| `build.gradle.kts`, `buildSrc/build.gradle.kts` | Explicit local bridge, mandatory lifecycle, ordering, inputs and focused test wiring |
| `buildSrc/src/test/java/io/mazewall/build/BuildLogicGateFunctionalTest.java` (recover) | Applied production DSL/block, lifecycle/cache/failure/ordering regressions |
| Existing `VerificationActionsTest.java`, `VerificationWorkflowInputFunctionalTest.java`, `CiCacheKeyFunctionalTest.java`, `CiCachePolicyTest.kt` | Retain current harness, workflow invalidation and actual foreign-ownership controls; adapt only where interfaces require |
| Existing `CheckCiCachePolicy.kt`, `CiCachePolicyTest.kt`, `.github/workflows/ci.yml` | Required build-logic lane, four-result join, retained security/cache/publication policy |
| `CONTRIBUTING.md`, `docs/internals/designs/core/testing-strategy.md`, `docs/internals/build/gradle-baseline.md` | Accurate local/CI commands and measured results; distinguish historical cache mechanism from current behavior |
| Scoped backlog issue/index and `docs/internals/evidence/2026-10-20-ci-rollout.md` | State, immutable run/artifact IDs, review decisions and concise acceptance evidence; no bulk logs in Git |

## Task 1: Verify merged default security and publication

**Interfaces:** Consume merge `512565a7` and run `39483882935`; produce immutable evidence of the actual default result. Read-only verification is not a new implementation experiment.

- [ ] Inspect current Git status and the run's actual SHA/job IDs. Watch the existing run once; do not launch a duplicate or poll a background watcher.
- [ ] Download logs/artifacts by exact ID, recording run/attempt/job/ref/SHA and timestamps. Name-only download can select the wrong artifact after a rerun.
- [ ] Confirm eligible security actually ran: completed `dependencyCheckUpdate`, ownership, explicit save, exact-key lookup/assertion, `dependencyCheckAnalyze`, report/CVSS outcome. A green job with skipped scanner steps does not qualify analysis.
- [ ] Inspect default-branch snapshot key/ref/version/size. It must be the completed default-ref snapshot, not a PR entry assumed promoted on merge.
- [ ] Verify core/native/demo, optional native acceptance, mutation scores/receipt and stable join. Then inspect the actual publication step/result and expected package coordinates/version; a green join is not proof of publication.
- [ ] Record durations, queue/setup/cache/update/analysis/publication times and total runner minutes. **Expected:** actual default security and publication succeed with unchanged gates. If not, retain the precise failure and keep rollout open.
- [ ] On failure, diagnose the failing step before retry. A bounded rerun may target the failed job and dependents after a justified correction; do not replay successful native pipelines merely to bootstrap NVD. No blind retry or automatic budget increase.

## Task 2: Prove default warm restoration and a later due refresh

**Consumes:** Task1's completed default snapshot and real security job ID. **Produces:** separately labeled warm and update-due evidence.

- [ ] Only after Task1's completed snapshot exists, run one targeted security rerun plus its dependent gates: `gh run rerun <run-id> --job <security-job-id>`. On a default push, this can rerun publication; verify same-version publication/idempotence behavior first and obtain the normal release approval. Do not assume it is a scan-only command.
- [ ] If same-version republishing is not safe/approved, use the next approved default push as the warm observation instead. Do not create a bootstrap workflow or alter publication guards just to obtain a measurement.
- [ ] Verify actual matching default snapshot download, caller ownership, successful normal update command, new run/attempt snapshot, exact lookup and fresh analysis. Compare task logs and scanner timestamps, not just job conclusions.
- [ ] **Expected warm result:** no full database bootstrap when compatible data is available; analysis still executes. Cache eviction/miss is recorded honestly, not called a warm pass.
- [ ] Observe one later approved run when the scanner's normal update interval is due. Confirm completed incremental checking/update, advancing Last Checked and a new completed snapshot, with real reanalysis. Never force this by changing validity, disabling updates, deleting caches or adding a retry loop.
- [ ] Record warm/due verification as pending until observed; no sleeping/polling for hours, new scheduler, or claim that an unchanged timestamp proves freshness advancement. After the default cold evidence review passes, local development and nonpublishing PR checks below may proceed with warm/due acceptance explicitly open. Do not claim rollout complete or bypass the approved release/merge gates. This revised start condition avoids requiring a benchmark republication merely to begin local TDD.
- [ ] Save concise results in the evidence document. Keep raw logs outside Git under `/tmp/opencode/ci-rollout-20261020/`; do not recreate either deleted folder.

## Task 3: Recover the complete local build-logic gate without losing newer fixes

**Interfaces:** root `buildLogicCheck: GradleBuild`, nested build name `build-logic-verification`, nested tasks exactly `[verificationActionsTest]`, one worker/no parallelism. Root `check` requires it; root build/mergeCheck retain full verification.

- [ ] After successful default cold evidence review, fetch master and inspect Git status. Start a fresh scoped branch from current `origin/master` in this checkout, not a worktree. Preserve unrelated work before branch operations. Warm/due observations remain open, and this branch cannot authorize publication or close that rollout. Create/update a scoped backlog work package using `./scripts/adkw new-issue`; do not auto-resolve unrelated issues.
- [ ] Read `git diff 40321646..f8f41c52 -- <each allowed source path>`. Selectively port reviewed implementation pieces; do NOT merge the old branch, copy whole newer files over, or cherry-pick its planning/docs commits. Reconcile with the actual latest master and recheck scope.
- [ ] **Tests first:** recover/adapt `BuildLogicGateFunctionalTest` and wire focused `buildLogicGateTest`. Applied buildSrc DSL graphs must exclude the verifier from `jar`, retain it for buildSrc `test/check`; applied actual root block must keep help/assemble isolated and check/build failure mandatory. Run `./gradlew -p buildSrc buildLogicGateTest --console=plain --max-workers=1 --no-parallel`. **Expected RED:** absent explicit gate or old implicit jar edge, not a missing dependency.
- [ ] Add direct actual ordering-edge assertions for representative root work, child `heavy` and same-named child `buildLogicCheck`, including reused strict configuration cache. Negative controls removing a heavy anchor and excluding by name must fail. Keep one worker; describe execution evidence as serial under parallel-enabled configuration, not concurrent stress.
- [ ] Implement the small delimited root bridge with public GradleBuild APIs, distinct name and nested limits. Root `check` depends on it; lazy `mustRunAfter(":buildLogicCheck")` applies to other root/child tasks, excluding only exact root gate path. Ordering alone must not select the gate.
- [ ] Remove ONLY buildSrc `jar -> verificationActionsTest`; retain its `test/check` edges, all cases/inputs and pre-execution marker deletion. Keep the workflow input and root-safe fixture selection. Declare newly read buildSrc settings as a named relative-path-sensitive input on the owning verifier/focused gate test. Preserve configuration-cache-safe task-local marker access without exemptions.
- [ ] Keep the current applied workflow-input fixture working with the recovered registration: prime SUCCESS, unchanged UP-TO-DATE, workflow-only edit SUCCESS, invalid workflow FAILED and old marker absent. If the recovered registration declares settings or another real file, stage that actual input in the independent fixture; do not remove the declaration to make it pass. Keep input-change failure under reused strict configuration cache in the root-gate fixture.
- [ ] **GREEN:** focused gate, `./gradlew -p buildSrc ciCachePolicyTest testKitCacheTest --console=plain --max-workers=1 --no-parallel`, and current workflow-input regression through its existing harness all pass. Actual help/assemble graphs exclude the gate; complete check/build graphs include it first. No recursive build of the active checkout in a fixture.
- [ ] Review this local-lifecycle diff and commit only its build/test files. **Expected:** no workflow/security/publisher/dependency/heap or deleted-folder restoration yet.

## Task 4: Add the required CI gate while retaining security

**Consumes:** Task3's exact root gate. **Produces:** independent `build-logic-validation` / `Build Logic Validation`, stable join needs `[core-quality, mutation-tests, security-check, build-logic-validation]` and four literal successes. Publication still depends on optional acceptance and that join.

- [ ] **Tests first:** adapt held policy/fixtures to CURRENT security-aware code. Assert missing/skipped/serialized/allowed-failure/unbounded build-logic lane, missing resource wrapper/strict cache/diagnostics, and loss of ANY security/publisher prerequisite are rejected. Preserve all current security metadata/ownership/snapshot/version-prefix/eligibility/upload-failure tests.
- [ ] Run the extracted actual join shell over all625 combinations of success/failure/cancelled/skipped/empty for four jobs; only all-success passes. Keep existing security/legacy projections with the extra result explicitly fixed to success, not an unresolved expression. Test publication guards and forwarded cache identity remain unchanged.
- [ ] Run `./gradlew -p buildSrc ciCachePolicyTest --console=plain --max-workers=1`. **Expected RED:** actual missing new lane/four-way policy. Then implement the independent Ubuntu26.04/JDK25 read-only90-minute lane with the existing setup-gradle policy and always-uploaded log/receipt/buildSrc diagnostics.
- [ ] Invoke exactly this workload, with pipefail around any tee:

```bash
python3 scripts/ci_resource_probe.py --output build/reports/ci-resources/build-logic.json -- \
  ./gradlew buildLogicCheck --configuration-cache --configuration-cache-problems=fail \
  --no-daemon --max-workers=1 --no-parallel
```

- [ ] Extend the stable always-evaluated join to all four prerequisites. Do not edit the security lifecycle, its version delimiter, scanner/root policy, native/mutation commands, output forwarding or publisher guard/permissions/command. No job-level skip or allowed failure for build logic.
- [ ] **GREEN:** focused policy plus `./gradlew checkCiCachePolicy --console=plain --max-workers=1` pass. Duplicate-reject actual YAML, syntax-check embedded shell and compare job maps to Task3 base: only new gate and required join may differ. The actual workflow-input invalidation test still passes.
- [ ] Commit this coherent workflow/policy/test change. Update testing strategy/CONTRIBUTING and the scoped issue with exact commands/four-way lifecycle; do not link deleted plan files.

## Task 5: Qualify the real suite, measure CI and complete the approved rollout

- [ ] Run Task4's actual resource-wrapped gate locally under strict configuration cache; require actual suite execution, known >=2GiB reserve and OOM delta0. Repeat unchanged once to prove cache reuse/UP-TO-DATE behavior, clearly labeling that second path as not full-workload memory proof. Do not fabricate markers, purge unrelated caches or increase heaps on failure.
- [ ] Run focused gate/policy/workflow-input/TestKit and `ktlintKotlinScriptCheck` checks. After green, run the complete included-module gate once:

```bash
./gradlew build -PincludeKoogIntegration=true -PkoogBootstrap=true \
  -PincludeSpringIntegration=true --console=plain --max-workers=2
```

- [ ] **Expected:** all selected checks pass, no lost inputs/cases, no native-cache bypass. Run `git diff --check`, changed-document reference checks and `./scripts/adkw check-backlog`; keep raw outputs outside Git. Existing historical links to deleted folders are recorded separately, not repaired by restoring those folders. Metadata-only evidence edits do not require repeating a complete build.
- [ ] Obtain one independent whole-change review after task checks. The reviewer must inspect current security join, workflow invalidation, root fixture, marker lifetime and real ordering edges. Fix actual blockers with RED/GREEN tests; do not treat earlier branch reviews as approval of this combined tree.
- [ ] Open a scoped PR through the normal path. First hosted run must actually execute the dedicated suite once with its own qualified receipt; other producer/adapter/security/publisher bootstraps must not execute that suite implicitly. Collect exact job/step logs, not a global count of nested fixture task names.
- [ ] Observe a same-head warm qualification without forced native-result reuse. Native/adapter outcomes remain fresh; host-only PIT or verifier outputs may be cached for matching inputs but must be labeled. Keep actual-execution memory receipts separately.
- [ ] Compare wall time, queue/setup/cache/execute time, runner minutes, gate duration and no-duplicate-suite evidence against qualified master results from Tasks1/2. Match cache warmth and scanner due/cold state; no end-to-end speed claim from a49-minute cold NVD run versus a warm scan. Use the baseline's <=10% warm unitCheck regression guard.
- [ ] Only after real required gates/review/memory evidence pass, enter the normal merge approval path; exact-head checks must still match. After approved merge, observe default security, the new gate and actual publication, not just the PR join. Preserve any still-pending due-refresh evidence as an open item.

## Follow-up boundaries and completion

- [ ] Keep the separate Portal retirement issue open. If its original failure recurs, retain the exact test iteration/final-close/retry and physical-owner predicate evidence before a behavioral fix. Do not weaken assertions/deadlines or run repeat-until-green probes as part of this optimization.
- [ ] Do not expand into dependency upgrades, global cache cleanup, historical document restoration or broad root-build portability. Actual UID0 execution remains a separate evidence gap; modeled fixture selection does not resolve all preexisting root restrictions.
- [ ] Completion requires evidence for default cold/warm/due security and publication, the new mandatory local/CI gate, real memory qualification, preserved fresh native checks, independent review and honest measured performance. If improvement is not demonstrated, report that rather than announcing a speedup.

## Plan self-review

- [x] Tasks1/2 cover rollout observations, not speculative code changes; targeted default reruns explicitly account for publication side effects.
- [x] Task3 preserves both merged review fixes and avoids resurrecting deleted folders; actual input/failure/ordering tests cover the known risks.
- [x] Task4 consumes the exact Task3 gate and retains security as the fourth prerequisite;625-way shell assertions and current policy fixtures agree.
- [x] Task5 separates local/hosted and cold/warm/cached/executed evidence, keeps all existing limits and routes merge/publication through approval.
- [x] File scope, task names, resource path and local/CI lifecycle agree. Portal fixes and broad portability remain separate; no implementation or retry was performed while writing this plan.
