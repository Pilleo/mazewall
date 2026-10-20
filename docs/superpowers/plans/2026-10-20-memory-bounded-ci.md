---
document_type: execution_plan
base_revision: 38943a8c2c362d234bc4391da7513a1135638683
---

# Memory-Bounded CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. The operator approved this plan and explicitly selected subagents on20October2026. Use serial implementers, task-scoped reviews and one whole-branch review. Steps use checkbox (`- [ ]`) syntax for tracking. No worktree or implicit merge is authorized.

**Goal:** Remove mutation execution from the main serial CI path without increasing memory pressure within any runner or weakening required checks.

**Architecture:** First correct and bound the actual PIT configuration, then measure aggregate runner memory. Move host-only mutation tasks to a separate runner and preserve the existing required main check as a fail-closed join. Keep native jobs, buildSrc lifecycle, cache identities and publication behavior intact.

**Tech Stack:** Existing Gradle9.6.1/Kotlin DSL, PIT Gradle plugin1.19.0, JDK25, GitHub Actions on ubuntu-26.04, Python3 standard library and Gradle TestKit.

**Spec:** [Memory-bounded mutation CI](../specs/2026-10-20-memory-bounded-ci-design.md). Read both documents.

## Global Constraints

- Use JDK 25 and `ubuntu-26.04`; preserve JDK 22+ library compatibility.
- Add no dependencies, dependency upgrades, native/runtime changes or worktrees.
- Parallelize across separate GitHub-hosted runners, never by backgrounding heavy commands in one checkout.
- Mutation CI uses `--max-workers=1 --no-parallel`, two PIT workers and one ordinary test fork; run the three module PIT tasks serially.
- Resolve PIT threads consistently: `-Pmazewall.pitest.threads`, then compatible `-Dpitest.threads`, then default `2`; reject non-integers and values below `1`.
- Bound the PIT parent to `-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m` and each minion to `-Xms128m -Xmx256m -XX:MaxMetaspaceSize=256m`; preserve `--enable-native-access=ALL-UNNAMED`.
- Preserve existing Gradle heap/metaspace settings, existing kernel forks, all mutation targets/exclusions/timeouts/thresholds, real assertions and fresh native execution.
- Require at least `2147483648` bytes of observed memory headroom and zero additional OOM kills on the new mutation runner; unknown memory evidence is not a qualified pass.
- Preserve both existing required check names, fail-closed publication, serial operator/orchestrator merges and PR-only cancellation.
- Preserve host/container cache separation, ownership checks, SHA/source identities and native-result non-caching; add no new container archive.
- Never modify, filter, or handle `GITHUB_TOKEN`; preserve existing credential bindings.
- Retain full local `./gradlew build` verification. Do not remove the buildSrc JAR's regression dependency in this change.

## Review Focus

1. Hidden nested concurrency: `-P...threads=2` must reach all three modules, and module mutation processes must not overlap; Task1 tests resolved settings, Task3 tests command/graph bounds and checks actual process receipts.
2. Tight cgroup inside a larger host: use the smallest visible headroom, not advertised runner RAM; Task2 tests a7GiB host with a3GiB cgroup and an almost-full ancestor.
3. Unknown/cancelled/OOM outcomes: retain the child failure, fail successful-but-unsafe runs, stop owned work and upload evidence; Task2 tests all four cases, Task3 tests always-upload wiring.
4. Skipped/failed mutation lane: neither merge acceptance nor publication may turn green; Task3 tests every prerequisite result and both event types.
5. Cache relocation and cold starts: preserve #545's location-independent input behavior and ownership boundaries; Task3 retains its real TestKit regression and qualifies cold/warm memory and duration separately.

---

## File responsibility map

| Task | Create/modify | Responsibility |
|---|---|---|
| 1 | Create `gradle/pitest-limits.gradle` | One shared Groovy Gradle configuration for thread resolution and PIT parent/minion caps; configure the existing extension without adding a plugin dependency |
| 1 | Modify `gradle.properties:24` | Remove the redundant project-property default so legacy `-D` is not masked; the shared script owns default2 |
| 1 | Modify `enforcer/build.gradle.kts:273-276`, `platform/build.gradle.kts:137-140`, `profiler/build.gradle.kts:848-851` | Apply the shared limits after each existing PIT block; remove conflicting local thread/JVM setters, keep all targets/floors |
| 1 | Create `buildSrc/src/test/java/io/mazewall/build/PitestLimitsFunctionalTest.java` | Real plugin/TestKit fixture for the shared limits across three projects |
| 1 | Modify `buildSrc/build.gradle.kts`, `buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java` | Focused `pitestLimitsTest` and mandatory regression entrypoint |
| 1 | Modify `CONTRIBUTING.md:92`, `docs/internals/designs/core/testing-strategy.md` | Canonical safe mutation command and legacy-property compatibility |
| 2 | Create `scripts/ci_resource_probe.py`, `scripts/tests/test_ci_resource_probe.py` | Read-only aggregate memory evidence and owned-command execution; standard-library tests |
| 3 | Modify `.github/workflows/ci.yml` | Separate runner, stable required-check join, cache-output forwarding and receipt uploads |
| 3 | Modify `buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt`, `buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt` | Graph, mutation-command bounds and fail-closed policy mutations |
| 3 | Modify `docs/internals/designs/core/testing-strategy.md` | Job graph, exact memory qualification and measured results/limits |
| All | Existing `docs/internals/backlog/issue-20261019-220939-759-bound-mutation-worker-memory-before-isolating-ci-m.md`, index | Scope and evidence; never archive without control-plane acceptance |

Read-only references: `gradle.properties`, `BuildPolicy.kt`, `TestKitCacheFunctionalTest.java`, container runner, existing mutation blocks, testing strategy and root/module AGENTS.md. No changes to security implementation, dependency declarations, wrapper, locks, cache archives or native test isolation.

## Before implementation

- [x] Obtain operator review of this spec/plan. #545 remains open; use its accepted head on a separate stacked branch, without merging or modifying that PR.
- [x] Inspect `git status` and current remote/default-branch state. Scoped branch `perf/memory-bounded-ci` starts from the accepted prerequisite; no worktree or history rewrite.
- [x] Read nearest instructions and inspect task-input/cache behavior preserved by #545. Current run39327392885 remains the timing reference; thresholds are retained below.
- [x] Run focused host baselines only; no concurrent local builds were launched to imitate isolated runners.

### Task 1: Make actual PIT concurrency and JVM budgets consistent

**Interfaces:**
- Consumes: existing PIT1.19.0 `PitestPluginExtension.threads`, `jvmArgs`, `mainProcessJvmArgs` provider properties.
- Produces: `gradle/pitest-limits.gradle`, applied by each owning module after its existing `pitest` block; no new public/library API.
- Test entrypoints: `PitestLimitsFunctionalTest.main(String[])` and `verify(Path repository)`; focused task `pitestLimitsTest` passes the repository root, as existing focused executable tests do.

- [ ] **Step 1: Write the failing real configuration tests.** The fixture copies the actual shared limits script and root gradle.properties, uses the existing PIT plugin version and three minimal projects, and prints each resolved extension. Do not assert script text. Pin these cases:

  | Arguments | Expected thread count in each project |
  |---|---:|
  | none | 2 |
  | `-Pmazewall.pitest.threads=1` | 1 |
  | `-Dpitest.threads=3` | 3 |
  | both `-P...=2` and `-D...=4` | 2 |
  | selected value `0`, `-1`, empty or `abc` | configuration failure |

  Assert exact parent/minion flags from Global Constraints, the native-access flag, and unchanged values of unrelated extension properties. Run the property-change case with configuration cache twice to reject stale provider values.

- [ ] **Step 2: Run RED.** `./gradlew -p buildSrc pitestLimitsTest --console=plain --max-workers=1`; expect the missing production script/configuration assertion, not an unrelated dependency/toolchain failure.
- [ ] **Step 3: Implement the shared Groovy Gradle limits script and apply it in all three modules.** Use lazy Gradle project/system-property providers with the stated precedence and configure the existing `pitest` extension through Groovy's normal DSL; add no plugin dependency, reflection or classloader workaround. Remove only superseded local setters and the redundant `mazewall.pitest.threads=2` entry from gradle.properties. Preserve thresholds `(coverage, mutation, strength)`: enforcer `(89,61,76)`, platform `(97,61,98)`, profiler `(93,65,75)` and their entire target/exclusion/timeout selections.
- [ ] **Step 4: Run GREEN.** Run `pitestLimitsTest` and the existing `ciCachePolicyTest`. Add the regression to `VerificationActionsTest`; keep its fixture independent of the real repository build to prevent recursive buildSrc verification. Update the canonical documentation command to:

  ```bash
  ./gradlew :enforcer:pitest :platform:pitest :profiler:pitest \
    --max-workers=1 --no-parallel \
    -Pmazewall.pitest.threads=2 -Pmazewall.test.maxParallelForks=1
  ```

- [ ] **Step 5: Commit the settings/regression/docs scope.** `git add --` only Task1 paths; `git commit -m "Bound and unify mutation JVM concurrency"`. Do not push or fan out jobs yet; memory qualification is next.

### Task 2: Measure memory without hiding workload failure

**Interfaces:**
- Create `scripts/ci_resource_probe.py`: `main(argv: Sequence[str]) -> int`, CLI `python3 scripts/ci_resource_probe.py --output <receipt.json> -- <command> [args...]`.
- Private frozen `MemorySample` fields: `headroom_bytes: int | None`, `peak_headroom_bytes: int | None`, `oom_kills: int | None`. Closed `MemoryVerdict` enum values are `pass`, `unsafe`, `unknown`. Pure `evaluate_samples(samples: Sequence[MemorySample]) -> MemoryVerdict` uses the first/last OOM counters and all samples; `collect_sample() -> MemorySample` owns Linux reads. Reserve is fixed `2147483648` bytes; sampling interval is1second, not new configuration knobs.
- Receipt schema1: `schema`, `argv`, `command_exit_code` (null before launch), `elapsed_seconds`, `minimum_headroom_bytes`, `cgroup_peaks`, `oom_kill_delta`, `qualification` (`pass|unsafe|unknown`), `reason`. Unknown data remains null/classified, never numeric zero.

- [ ] **Step 1: Write standard-library tests** in `scripts/tests/test_ci_resource_probe.py`:7GiB host/3GiB cgroup chooses the cgroup budget; a constrained ancestor wins; `memory.max=max` uses remaining real bounds; exactly2GiB passes and one byte less fails; missing/malformed required counters produce unknown; OOM delta1 is unsafe even if the command succeeds. No test allocates gigabytes or triggers a real OOM.
- [ ] **Step 2: Add owned-command tests** using tiny Python children: exit42 stays42; success under unsafe/unknown evidence exits nonzero; initial insufficient headroom does not launch the child; normal output is unchanged; SIGTERM interrupts the owned child group, retires the sampler and returns143. Use fixture Linux files for unit decisions and a bounded live smoke for actual collection. Do not scrape or print the environment.
- [ ] **Step 3: Run RED.** `python3 -m unittest discover -s scripts/tests -p 'test_ci_resource_probe.py' -v`; expect the missing module/implementation, then assertion-level failure before adding memory decisions.
- [ ] **Step 4: Implement the collector/runner.** Read host memory and the process's actual visible cgroup-v2 ancestry. Sample before launch, every second and after completion; retain peak/OOM evidence without resetting kernel counters. Preserve nonzero command exits ahead of diagnostic errors. Keep stdout/stderr inherited. Handle cancellation with bounded cleanup of acquired children only; do not retry, change swap, stop unrelated daemons or loosen workload timeouts.
- [ ] **Step 5: Run GREEN and qualify the bounded command.** Run the unittest command, then wrap Task1's exact three-task mutation command with the new probe. Require real mutation reports at unchanged floors, a schema1 qualified receipt, at least2GiB minimum headroom and no OOM increase. If local counters are unsupported, record local qualification as unknown and require hosted evidence; do not claim a safe run from logs alone.
- [ ] **Step 6: Commit only Task2 files/evidence references.** `git commit -m "Record mutation-runner memory headroom and OOM evidence"`. If caps fail, stop for review; no automatic heap increases, thread increases or test exclusions.

### Task 3: Isolate the mutation runner while preserving required checks

**Interfaces:**
- Independent jobs: `optional-integrations` (unchanged), `core-quality` (old implementation minus PIT), `mutation-tests` (host mutation command through Task2 CLI).
- Required success join: `build-test-publish`, exact display name `Build, Test, Security Scan & Publish`, `needs: [core-quality, mutation-tests]`, `if: always()` and explicit success tests for both results.
- Join command: `test "${{ needs.core-quality.result }}" = success && test "${{ needs.mutation-tests.result }}" = success`. The fixture substitutes result values into this actual shell command and asserts its exit status; do not test a separate mocked decision helper.
- `core-quality.outputs.container_cache_hash` is forwarded as `build-test-publish.outputs.container_cache_hash`; publication keeps its existing reference and prerequisites.
- Preserve `ciCachePolicyViolations(workflow: String): List<CiCacheViolation>`; extend existing policy checks/mutation fixtures rather than introducing another workflow parser.

- [ ] **Step 1: Write policy RED cases.** In `CiCachePolicyTest.run()`, model the new valid graph and assert exact violations when mutation is missing, job-level skipped, serialized behind core, allowed to fail, colocated as a background command, lacks worker/thread/fork bounds, or lacks always-uploaded receipts. Assert the three independent runners use `ubuntu-26.04`. Exercise each join prerequisite result `success`, `failure`, `cancelled`, `skipped`, `null`: only two successes pass. Test PR versus default-branch-push publication, output forwarding and unchanged PR-only cancellation. Retain every old cache-identity/save/host-container regression.
- [ ] **Step 2: Run RED.** `./gradlew -p buildSrc ciCachePolicyTest --console=plain --max-workers=1`; verify the new valid/unsafe graph assertions fail for the current implementation rather than relaxing old fixtures.
- [ ] **Step 3: Implement the exact job graph.** Rename the implementation job and move only the PIT command. The mutation runner uses checkout/JDK25/setup-gradle (`cache-read-only: false`), independent code filtering and Task2's wrapper around Task1's command. It has no Podman/native setup or new archive. Both code filters include `.github/workflows/ci.yml`; a docs-only mutation step may skip but its job succeeds. Put no `needs` or job-level `if` on the three independent lanes. Keep the existing main required check as the explicit always-evaluated join, forwarding the core cache identity. Do not change publication commands, credentials or branch protection.
- [ ] **Step 4: Run GREEN and compare actual YAML semantics.** Run `ciCachePolicyTest`, root `checkCiCachePolicy`, and `testKitCacheTest`. Parse YAML with the available validator and compare commands against the base: every prior gate remains, PIT runs exactly once for a code change, both required names remain, only publication has package-write permission. Confirm no same-runner heavy backgrounding and no change to native cache-disable/fresh-JVM rules.
- [ ] **Step 5: Run the completion gate once after focused checks pass.**

  ```bash
  ./gradlew build -PincludeKoogIntegration=true -PkoogBootstrap=true \
    -PincludeSpringIntegration=true --continue --console=plain --max-workers=2
  ./scripts/adkw check-backlog
  git diff --check
  ```

  Require a successful complete build and healthy test aggregate. Run the script unittest command separately because it is not implicitly part of root Gradle. Check spec/plan/docs links and exact scope. Do not run multiple local Gradle builds simultaneously.

- [ ] **Step 6: Obtain one independent whole-change review after the task-scoped subagent reviews, then commit/push a separate PR.** Review memory accounting, actual PIT JVM flags, required-check semantics, cache trust/ownership and retained assertions. Preserve #545 history and unrelated work. Merge is a separate operator decision.
- [ ] **Step 7: Hosted acceptance before claiming improvement.** Collect one cold and one unchanged-head warm run. Require independent runner starts, all three real mutation reports, the same kernel/native/adapter assertions, green stable required checks, no OOM delta, qualified memory receipts and at least2GiB headroom. Report wall time, queue/setup/cache/execute times and total runner minutes against run39327392885. If evidence is unknown or budgets fail, stop rollout; do not rerun blindly, move workloads onto one runner, drop targets or raise limits automatically. PR publication must skip; actual release publication is qualified only on a later approved default-branch run.

## Self-review and handoff

Implementation checkpoint: Task1 `d7cdaf63` and Task2 `1821ecf4`/`0cae7925`
passed task-scoped review; Task3 `dbc4d084`/`27016eb1` passed focused checks with
its sole review minor corrected. The complete local optional build passes286
tasks; final Python34-test, backlog and whitespace checks pass. One whole-branch
review and hosted cold/warm final-code memory qualification remain pending. The
earlier all-module memory witness predates final parser/cancellation fixes and is
not represented as a current-head hosted run. See the scoped backlog evidence.

Final local checkpoint: whole-branch findings are addressed by `392ad367` and the
operator-authorized retirement-sampling fix `e159fca8`; final scoped re-review is
spec PASS/quality APPROVE. The corrected full optional build passes271 tasks in
4m33s with configuration-cache reuse; all41 Python tests and strict YAML/link,
backlog and whitespace checks pass. The earlier checkpoint above is historical.
Draft stacked PR/hosted cold-warm qualification remain next; PR545 is not merged
implicitly, and no final-code hosted load or CI speed claim is made.

- [x] Spec coverage: memory limits/evidence, compatibility, cache boundaries, failure graph and rollout are assigned to Tasks1-3.
- [x] Step/type scan: file names, CLI, output wire values, PIT settings and cache-output forwarding agree with the spec.
- [x] Review focus: each of the five conditions above has an owning test/acceptance step.
- [x] Scope/proportion: one bounded CI scheduling change; no speculative build-tool refactor, new cache service or native parallelism.
- [x] Operator reviewed and approved the spec/plan; latest instruction explicitly selects subagents. Preserve this choice rather than the superseded native/inline method.
