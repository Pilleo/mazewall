---
title: Gradle build modernization implementation plan
document_type: execution_plan
base_revision: 3a0d0115
severity: ENHANCEMENT
status: completed
priority: medium
component: build
target_modules: []
target_files:
  - build.gradle.kts
  - settings.gradle.kts
  - buildSrc
effort: large
autonomy: supervised
open_questions: false
has_side_effects: false
---

# Gradle Build Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make mazewall's Gradle build faster, deterministic, configuration-cache-safe, side-effect-free, and easier to maintain without weakening any security, test, coverage, or publishing gate.

**Architecture:** Move shared build policy from the root script into small precompiled convention plugins in `buildSrc`, keep repository and dependency policy centralized in settings/catalog files, and expose explicit host and kernel verification lifecycles. Every migration preserves an old-vs-new task-graph assertion until the final cleanup task, so performance work cannot silently remove a gate.

**Tech Stack:** Gradle 9.6.1 Kotlin DSL, Kotlin/JVM 2.4, JDK toolchains 22/25, JUnit 5, Gradle TestKit, JaCoCo, Detekt, KtLint, SpotBugs, OWASP Dependency Check, GitHub Actions.

**Spec:** This plan implements the ten Gradle findings recorded under **Required outcomes** below and is constrained by `AGENTS.md`, `.agents/CODE_QUALITY.md`, and each affected module's nearest `AGENTS.md`.

## Global Constraints

- Preserve JDK 22 bytecode/API compatibility and JDK 25 build/test toolchains.
- Do not add runtime dependencies or change published Maven coordinates.
- Keep host-safe tests separate from tests that install Seccomp, Landlock, or USER_NOTIF behavior.
- Keep `./gradlew build` as the documented merge gate; changing its internals must not reduce coverage.
- Never read, rewrite, filter, print, or otherwise handle `GITHUB_TOKEN` beyond Gradle's existing credentials provider.
- Configuration-cache reuse must pass twice from the same checkout.
- Formatting, diagram generation, Git-hook installation, and external Maven reports must be explicit commands and must not mutate a normal `check` or `build` invocation.
- Each task is committed separately and must leave the build usable.

## Required outcomes

1. A reproducible performance and task-graph baseline exists before structural changes.
2. Repositories and all build/dependency versions have one source of truth.
3. Shared configuration lives in focused, tested convention plugins instead of the root script.
4. Compile and check tasks never auto-format source files.
5. Host-safe and privileged-kernel verification have explicit, non-overlapping lifecycles.
6. Eager project/task/configuration access and reflective plugin configuration are removed.
7. Failure triage runs only for an actual failed test invocation and does not finalize every test task.
8. Normal verification has no checkout-mutating or external-report side effects.
9. Worker, fork, and heap settings are coherent, provider-driven, and measurable on local and CI hosts.
10. Dependency resolution is reproducible and CI proves both correctness and configuration-cache reuse.

---

### Task 1: Freeze behavior and performance baselines

**Files:**
- Create: `buildSrc/src/test/kotlin/io/mazewall/build/BuildLifecycleFunctionalTest.kt`
- Create: `scripts/measure_gradle.sh`
- Create: `docs/internals/build/gradle-baseline.md`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: current task names `unitCheck`, `kernelCheck`, `build`, and module `Test` tasks.
- Produces: `measureGradle` shell contract and TestKit assertions used to prove later tasks preserve the intended lifecycle.

- [ ] **Step 1: Add a TestKit fixture that records the current task graph**

  Create `BuildLifecycleFunctionalTest` with temporary projects that apply the build conventions and assert that `unitCheck`, `kernelCheck`, and `build` exist. Record the intended end-state assertions now: `unitCheck` contains no `integrationTest`; `kernelCheck` contains both `integrationTest` and `integrationTestFreshJvm`; `build` depends on both aggregate gates only at the root.

- [ ] **Step 2: Verify the end-state lifecycle test initially fails**

  Run `./gradlew :buildSrc:test --tests io.mazewall.build.BuildLifecycleFunctionalTest`.
  Expected: failure showing the current `check` wiring pulls every `Test` task into module checks and therefore does not keep host/kernel lifecycles disjoint.

- [ ] **Step 3: Add a read-only timing script**

  Implement `scripts/measure_gradle.sh` to run `help`, `unitCheck`, and `build` with `--profile`, once cold and once warm, then print elapsed seconds, configured-project count, executed-task count, cache hits, and configuration-cache reuse. The script must write only under `build/reports/gradle-baseline/` and accept `--label <name>`.

- [ ] **Step 4: Capture the baseline and document the comparison contract**

  Run `./scripts/measure_gradle.sh --label before-modernization`. In `gradle-baseline.md`, record exact machine/runner information and define acceptance: warm `help` reuses configuration cache; warm `unitCheck` does not regress by more than 10%; no end-state task graph has more verification tasks than the baseline unless documented as a correctness fix.

- [ ] **Step 5: Add an informational CI artifact and commit**

  Upload `build/reports/gradle-baseline/` with `if: always()` and no required-status change. Commit only the fixture, script, document, and workflow edit as `test(build): capture Gradle lifecycle baseline`.

### Task 2: Centralize repositories and version declarations

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `buildSrc/build.gradle`
- Modify: `demos/agent-sandbox-demo/build.gradle.kts`
- Modify: `demos/vulnerable-web-app/build.gradle.kts`
- Modify: `demos/cli-demo/build.gradle.kts`

**Interfaces:**
- Consumes: the existing `libs` version catalog.
- Produces: repository policy enforced by `RepositoriesMode.FAIL_ON_PROJECT_REPOS` and catalog aliases for every non-fixture version.

- [ ] **Step 1: Add a failing repository-policy functional test**

  Extend `BuildLifecycleFunctionalTest` with a temporary subproject that declares `repositories { mavenCentral() }`; assert configuration fails with the Gradle project-repository rejection message.

- [ ] **Step 2: Enforce settings-owned dependency repositories**

  In `dependencyResolutionManagement`, set `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)`. Remove `repositories` blocks from `allprojects`, subprojects, and `buildSrc` where settings/plugin management already supplies the repository. Keep JitPack only if `./gradlew dependencies` proves a resolved production artifact still requires it; otherwise remove it.

- [ ] **Step 3: Move hard-coded build versions into the catalog**

  Add aliases for KtLint engine `1.7.0`, FindSecBugs `1.13.0`, SLF4J NOP `2.0.13`, JUnit Platform launcher, Spring Boot `3.4.0`, Spring Kotlin plugin `2.4.0`, LangChain4j `0.33.0`, H2 `2.2.224`, vulnerable-demo Log4j `2.14.1`, and XStream `1.4.17`. Preserve deliberately vulnerable versions under names prefixed `vulnerable-` so automated upgrades do not obscure their purpose.

- [ ] **Step 4: Remove duplicate/inconsistent declarations**

  Replace the `plantuml-core` literal `1.2023.13` with `version.ref = "plantuml"`, replace the vulnerable app's JaCoCo `0.8.12` with the catalogued `0.8.14`, and use catalog aliases/BOM aliases in all demo scripts. Do not upgrade behavior in this task; only deduplicate current intended versions.

- [ ] **Step 5: Verify and commit**

  Run `./gradlew help`, `./gradlew dependencies`, the repository-policy TestKit test, and `./scripts/check_gradle_lazy_resolution.sh`. Commit as `build: centralize repositories and versions`.

### Task 3: Extract maintainable convention plugins

**Files:**
- Replace: `buildSrc/build.gradle` with `buildSrc/build.gradle.kts`
- Create: `buildSrc/settings.gradle.kts`
- Create: `buildSrc/src/main/kotlin/mazewall.base-conventions.gradle.kts`
- Create: `buildSrc/src/main/kotlin/mazewall.jvm-library-conventions.gradle.kts`
- Create: `buildSrc/src/main/kotlin/mazewall.test-conventions.gradle.kts`
- Create: `buildSrc/src/main/kotlin/mazewall.quality-conventions.gradle.kts`
- Create: `buildSrc/src/main/kotlin/mazewall.publishing-conventions.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: all nine included subproject `build.gradle.kts` files

**Interfaces:**
- Produces plugin IDs `mazewall.base-conventions`, `mazewall.jvm-library-conventions`, `mazewall.test-conventions`, `mazewall.quality-conventions`, and `mazewall.publishing-conventions`.
- Preserves public Gradle task names consumed by CI and scripts.

- [ ] **Step 1: Add failing convention-plugin TestKit tests**

  For each plugin ID, create a minimal temporary build and assert its contract: group/version; Java release 22 and toolchain 25; JUnit platform/native-access/test heap; Detekt/KtLint/SpotBugs defaults; conditional GitHub Packages publishing. Run `./gradlew :buildSrc:test`; expected failure because the plugins do not exist.

- [ ] **Step 2: Create the five precompiled script plugins**

  Keep each plugin focused: base identity/providers; JVM compiler/toolchain; test process/logging; static analysis/coverage; publishing. Put shared constants in `buildSrc/src/main/kotlin/io/mazewall/build/BuildPolicy.kt` as immutable values.

- [ ] **Step 3: Migrate one representative module**

  Apply the plugins to `:platform`, remove equivalent local/root configuration, and run `:platform:tasks`, `:platform:compileKotlin`, `:platform:test`, and the TestKit suite. Compare JVM args and task dependencies with Task 1's recorded contract.

- [ ] **Step 4: Migrate remaining modules and slim the root script**

  Migrate `:enforcer`, `:profiler`, `:portal`, `:portal-codegen`, `:portal-worker`, and demos. The root script retains only root aggregate tasks, shared-test source-set ownership until Task 5, dependency scanning, and root-only utilities.

- [ ] **Step 5: Verify and commit**

  Run `./gradlew help` twice, `./gradlew :buildSrc:test`, `./gradlew unitCheck`, and `./gradlew build`. The second `help` must state configuration-cache reuse. Commit as `refactor(build): extract convention plugins`.

### Task 4: Make formatting explicit and verification read-only

**Files:**
- Modify: `buildSrc/src/main/kotlin/mazewall.jvm-library-conventions.gradle.kts`
- Modify: `buildSrc/src/main/kotlin/mazewall.quality-conventions.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `buildSrc/src/test/kotlin/io/mazewall/build/FormattingLifecycleFunctionalTest.kt`
- Modify: `CONTRIBUTING.md` or the repository's existing contributor command section

**Interfaces:**
- Produces explicit `format` aggregate task.
- Guarantees `compileKotlin`, `check`, `build`, and sources-JAR tasks do not depend on any `*Format` task.

- [ ] **Step 1: Write the failing task-graph test**

  Assert that a dry run of `compileKotlin`, `check`, and `build` contains no task whose name ends with `Format`, while `format` includes all applicable KtLint format tasks. Current wiring must fail this test.

- [ ] **Step 2: Remove mutating dependencies**

  Delete `KotlinCompile.dependsOn("ktlintFormat")`, KtLint check-to-format dependencies, and `kotlinSourcesJar.dependsOn("ktlintFormat")`. Configure check tasks to report formatting failures without editing files.

- [ ] **Step 3: Add an explicit aggregate formatter**

  Register root `format` depending lazily on applicable subproject `ktlintFormat` tasks. Demo exclusions remain explicit convention-plugin policy, not name-scanning `tasks.configureEach` blocks.

- [ ] **Step 4: Prove build immutability**

  Record `git status --porcelain`, run `./gradlew compileKotlin check`, record status again, and assert the two outputs are identical. Then introduce a temporary malformed Kotlin fixture, verify `ktlintCheck` fails without rewriting it, remove the fixture, and run `./gradlew format` separately.

- [ ] **Step 5: Document and commit**

  Document `./gradlew format` for opt-in rewriting and `./gradlew check` for read-only validation. Commit as `build: separate formatting from verification`.

### Task 5: Separate host and kernel verification lifecycles

**Files:**
- Modify: `buildSrc/src/main/kotlin/mazewall.test-conventions.gradle.kts`
- Modify: `buildSrc/src/main/kotlin/mazewall.quality-conventions.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `enforcer/build.gradle.kts`
- Modify: `platform/build.gradle.kts`
- Modify: `profiler/build.gradle.kts`
- Modify: `portal/build.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Modify: `buildSrc/src/test/kotlin/io/mazewall/build/BuildLifecycleFunctionalTest.kt`

**Interfaces:**
- Produces module/root `unitCheck`, module/root `kernelCheck`, and root `mergeCheck`.
- `unitCheck` consumes only host-safe `test` plus static analysis and unit coverage.
- `kernelCheck` consumes `integrationTest`, `integrationTestFreshJvm`, and kernel/combined coverage.
- `build` delegates to `mergeCheck` to preserve the documented merge command.

- [ ] **Step 1: Activate the failing end-state assertions from Task 1**

  Assert exact dry-run membership for each lifecycle and assert `unitCheck` can run on a host without privileged kernel features. Verify failure against current `check.dependsOn(tasks.withType<Test>())` wiring.

- [ ] **Step 2: Remove catch-all Test dependencies**

  Delete `check.dependsOn(tasks.withType<Test>())`. Register integration suites lazily by name and wire only those suites to `kernelCheck`; wire `test`, `detektMain`, KtLint check, SpotBugs main, and unit JaCoCo verification to `unitCheck`.

- [ ] **Step 3: Define the root merge lifecycle**

  Register `mergeCheck` depending on root `unitCheck`, root `kernelCheck`, dependency/security validation required for merges, and non-mutating structural checks. Make root `build` depend on `mergeCheck`; module `build` remains a normal Gradle assemble/check lifecycle and must not pull unrelated projects.

- [ ] **Step 4: Align CI stages**

  Host stage runs `./gradlew unitCheck`; the container stage runs `./gradlew kernelCheck` plus packaging gates; the final merge-equivalence assertion verifies both completed for the same SHA. Avoid running unit tests twice in the container unless combined JaCoCo data specifically requires them; if required, consume the host `.exec` artifact or document the intentional rerun.

- [ ] **Step 5: Verify and commit**

  Run dry runs for all three lifecycles, `./gradlew unitCheck`, `./gradlew kernelCheck`, and `./gradlew build`. Commit as `build: separate host and kernel verification`.

### Task 6: Remove eager and reflective Gradle configuration

**Files:**
- Modify: `buildSrc/src/main/kotlin/mazewall.quality-conventions.gradle.kts`
- Modify: `buildSrc/src/main/kotlin/mazewall.test-conventions.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `portal/build.gradle.kts`
- Modify: `scripts/check_gradle_lazy_resolution.sh`
- Create: `buildSrc/src/test/kotlin/io/mazewall/build/ConfigurationCacheFunctionalTest.kt`

**Interfaces:**
- Produces provider-backed classpaths and typed plugin configuration with no `evaluationDependsOn`, `findByName`, `classDirectories.files`, or reflective `getJdkHome` access.

- [ ] **Step 1: Extend the lazy-resolution guard and write failing tests**

  Reject `evaluationDependsOn(`, `.resolvedConfiguration`, `.files.map`, `tasks.findByName(`, `javaClass.getMethod(`, and configuration resolution inside task registration. Add a TestKit build that runs `help` twice with `--configuration-cache --configuration-cache-problems=fail`.

- [ ] **Step 2: Replace Detekt reflection with its typed API**

  Configure Detekt's JDK home through the version-supported typed task/extension property. If Detekt 2.0's public API lacks the property, remove the override and rely on the Gradle Java toolchain; do not retain reflection or a swallowed `NoSuchMethodException`.

- [ ] **Step 3: Remove cross-project evaluation ordering**

  Replace root `evaluationDependsOn(":profiler")` and portal `evaluationDependsOn(":portal-worker")` with resolvable/consumable configurations and explicit task inputs/dependencies. Keep `PortalWorkerClasspathArgProvider` as the execution-time boundary.

- [ ] **Step 4: Make coverage inputs lazy**

  Build class directories with `Provider<FileTree>` and `ConfigurableFileCollection.from(provider { ... })` without calling `.files` during configuration. Replace `tasks.findByName("processResources")` with plugin-scoped `tasks.named<ProcessResources>(...)` only where that task is guaranteed.

- [ ] **Step 5: Verify and commit**

  Run the lazy-resolution script, TestKit cache test, `./gradlew help` twice, `./gradlew unitCheck` twice, and inspect the second run for configuration-cache reuse. Commit as `build: remove eager configuration paths`.

### Task 7: Make failure triage explicit and accurate

**Files:**
- Modify: `build.gradle.kts`
- Modify: `buildSrc/src/main/kotlin/mazewall.test-conventions.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Create: `buildSrc/src/test/kotlin/io/mazewall/build/TriageLifecycleFunctionalTest.kt`

**Interfaces:**
- Produces `runTriage --failed-task <path>` or equivalent typed input.
- CI invokes triage only after a failed Gradle verification step.

- [ ] **Step 1: Write failing triage lifecycle tests**

  Assert a passing `test` dry run does not include `runTriage`, a deliberately failing fixture can invoke triage with the failed task path, and multiple test tasks do not schedule duplicate triage executions.

- [ ] **Step 2: Remove global finalizers and task-state scanning**

  Delete every `Test.finalizedBy(rootProject.tasks.named("runTriage"))` and the provider that reads `task.state.failure`. Task outcome is execution state, not a declarative task input, and must not determine configuration-cache behavior.

- [ ] **Step 3: Give triage typed inputs and bounded output**

  Add `@Input failedTaskPath`, `@OutputDirectory reportDirectory`, and a `--failed-task` argument provider to the triage task. Output goes under `build/reports/triage/<sanitized-task-path>/` and must redact environment values.

- [ ] **Step 4: Invoke triage from CI failure handling**

  Capture the failed Gradle task path/status from the host or container step, then run `./gradlew runTriage -Ptriage.failedTask=<path>` only under `if: failure()`. Upload the report with `if: always()`.

- [ ] **Step 5: Verify and commit**

  Run passing/failing TestKit fixtures, `./gradlew unitCheck --dry-run`, and a controlled failing test workflow. Commit as `build: decouple triage from test finalizers`.

### Task 8: Remove checkout and external-tool side effects from verification

**Files:**
- Modify: `build.gradle.kts`
- Modify: `enforcer/build.gradle.kts`
- Modify: `profiler/build.gradle.kts`
- Modify: `scripts/git-audit-hook.sh` documentation/caller if needed
- Modify: `.github/workflows/ci.yml`
- Create: `buildSrc/src/test/kotlin/io/mazewall/build/SideEffectFreeBuildFunctionalTest.kt`

**Interfaces:**
- Produces explicit `installGitHooks`, `generateClassDiagrams`, and `refactorFirstReport` maintenance commands.
- Guarantees none are dependencies of `check`, `build`, `unitCheck`, `kernelCheck`, or `mergeCheck`.

- [ ] **Step 1: Write a failing side-effect task-graph test**

  Assert dry runs of normal verification omit `installGitHooks`, `generateClassDiagrams`, and `refactorFirstReport`; assert each explicit task still exists. Current `check` and local module `build` wiring must fail.

- [ ] **Step 2: Detach Git-hook installation**

  Remove `check.dependsOn(installGitHooks)`. Keep the worktree-aware path resolution only inside the explicit task and document `./gradlew installGitHooks` as developer setup.

- [ ] **Step 3: Detach diagrams and external Maven reports**

  Remove module `build.dependsOn(generateClassDiagrams)` and root `check.dependsOn(refactorFirstReport)`. Register aggregate `updateDiagrams`; let CI run `refactorFirstReport` as non-blocking scheduled/manual analysis if Maven is available.

- [ ] **Step 4: Prove a clean checkout remains clean**

  Snapshot `git status --porcelain`, run `./gradlew build`, compare status byte-for-byte, then run each maintenance task explicitly and verify only its declared files change.

- [ ] **Step 5: Verify and commit**

  Run side-effect TestKit tests, `./gradlew build`, and `./gradlew updateDiagrams --dry-run`. Commit as `build: make verification side-effect free`.

### Task 9: Tune parallelism, forks, and memory as one resource budget

**Files:**
- Modify: `gradle.properties`
- Modify: `buildSrc/src/main/kotlin/mazewall.test-conventions.gradle.kts`
- Modify: `enforcer/build.gradle.kts`
- Modify: `platform/build.gradle.kts`
- Modify: `profiler/build.gradle.kts`
- Modify: `portal/build.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/internals/build/gradle-baseline.md`

**Interfaces:**
- Consumes Gradle properties `mazewall.test.maxParallelForks`, `mazewall.test.heap`, and `mazewall.pitest.threads` through `providers.gradleProperty`.
- Produces a documented resource equation: daemon heap + concurrent compiler workers + concurrent test JVM heaps + analysis-tool heaps must fit the runner memory limit.

- [ ] **Step 1: Add property-default functional tests**

  Assert defaults are safe (`maxParallelForks = 1` for kernel tests, bounded unit-test forks, 256 MiB test heap), and property overrides change task configuration without invalidating configuration-cache reuse.

- [ ] **Step 2: Remove ineffective/duplicated knobs**

  Remove non-standard `org.gradle.parallel.threads`; retain `org.gradle.workers.max` as the single Gradle worker ceiling. Remove repeated `-Xmx256m`/native-access args from modules now covered by conventions.

- [ ] **Step 3: Introduce provider-driven profiles**

  Define conservative defaults in `gradle.properties`. Set CI overrides from known runner memory/CPU, not `Runtime.availableProcessors()` alone. Keep kernel tests `maxParallelForks = 1` and preserve `forkEvery = 1` where process contamination is part of the test threat model.

- [ ] **Step 4: Benchmark three representative workloads**

  Use `measure_gradle.sh` for `help`, `unitCheck`, and `build` with baseline, conservative, and CI profiles. Reject any profile that OOMs, causes descriptor/thread flakes, or makes warm `unitCheck` more than 10% slower without a memory-stability justification.

- [ ] **Step 5: Verify and commit**

  Run two consecutive `unitCheck` executions and one full `build` under the selected defaults, record peak RSS and elapsed time, and commit as `perf(build): tune Gradle resource budgets`.

### Task 10: Lock reproducibility and enforce the final CI contract

**Files:**
- Modify: `settings.gradle.kts`
- Create or modify: `gradle/verification-metadata.xml`
- Create: `gradle/dependency-locks/` lockfiles for resolvable production/build configurations
- Modify: `buildSrc/src/main/kotlin/mazewall.base-conventions.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/internals/build/gradle-baseline.md`
- Modify: `docs/superpowers/plans/2026-09-08-gradle-build-modernization.md`

**Interfaces:**
- Produces checksum verification, dependency locking, the final merge-gate contract, and before/after performance evidence.

- [ ] **Step 1: Add a failing reproducibility check**

  Add `verifyGradleReproducibility` that fails when dependency verification metadata or lock state is missing/out of date. Exclude deliberately dynamic/local artifacts only with an inline rationale and exact coordinate.

- [ ] **Step 2: Generate and review verification metadata**

  Run Gradle's checksum metadata generation for SHA-256, inspect every newly trusted artifact, and commit signatures/checksums. Do not enable lenient verification.

- [ ] **Step 3: Enable dependency locking**

  Activate locking for resolvable production, build-tool, and test-runtime configurations. Generate lock state with `--write-locks`; keep deliberately vulnerable demo coordinates locked at their intentional versions.

- [ ] **Step 4: Enforce clean, cached, equivalent CI runs**

  CI runs `verifyGradleReproducibility`, `unitCheck`, `kernelCheck`, and the merge-equivalence task. Add a second `help`/lightweight `unitCheck` invocation that must report configuration-cache reuse, and upload build scans/profiles only as artifacts—never credentials.

- [ ] **Step 5: Run final verification and update evidence**

  Run `./scripts/check_gradle_lazy_resolution.sh`, `./gradlew :buildSrc:test`, `./gradlew verifyGradleReproducibility`, `./gradlew unitCheck`, `./gradlew kernelCheck`, and `./gradlew build` twice. Record before/after configuration time, execution time, executed tasks, cache hits, and peak RSS.

- [ ] **Step 6: Update operator documentation and commit**

  Document `format`, `unitCheck`, `kernelCheck`, `mergeCheck`, `build`, `updateDiagrams`, `installGitHooks`, dependency-lock updates, and triage invocation. Mark all ten required outcomes with evidence links in this plan and commit as `build: enforce reproducible Gradle verification`.

## Rollout and rollback

- Land tasks in numeric order; Tasks 3–8 depend on Task 1's TestKit contract, Task 5 depends on Task 3, and Task 10 depends on all preceding tasks.
- Keep each task as its own commit so a configuration-cache, CI, or kernel-runner regression can be reverted independently.
- Do not combine dependency upgrades with this modernization. Version upgrades get separate review after locks and verification metadata exist.
- If host and kernel JaCoCo data cannot be transported safely between CI jobs, retain the intentional unit-test rerun inside `kernelCheck` temporarily and record its time cost; do not weaken coverage thresholds.
- Roll back a task if it removes a gate, dirties the checkout during `build`, breaks configuration-cache reuse, or exceeds the documented memory budget.

## Final acceptance checklist

- [ ] `./gradlew help` reuses the configuration cache on its second run.
- [ ] `./gradlew unitCheck` is host-safe and contains no privileged integration task.
- [ ] `./gradlew kernelCheck` runs all Seccomp/Landlock/USER_NOTIF integration and fresh-JVM tests.
- [ ] `./gradlew build` remains the complete merge gate.
- [ ] `compileKotlin`, `check`, and `build` do not format or otherwise modify tracked files.
- [ ] No normal verification task installs Git hooks, invokes Maven/RefactorFirst, or regenerates diagrams.
- [ ] Repository policy rejects project-local dependency repositories.
- [ ] No uncatalogued non-fixture version remains in Kotlin/Groovy build scripts.
- [ ] Lazy-resolution and configuration-cache TestKit suites pass.
- [ ] Dependency verification and lock validation pass on a fresh checkout.
- [ ] All original coverage thresholds remain equal or stricter.
- [ ] The before/after report shows task counts, cache reuse, elapsed time, and peak RSS for local and CI profiles.
