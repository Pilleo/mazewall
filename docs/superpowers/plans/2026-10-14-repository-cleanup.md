---
document_type: execution_plan
base_revision: 96789e513136948338870ce7255fdec14e22c162
---

# Repository Cleanup Implementation Plan

> **For agentic workers:** Use `executing-plans` inline, with a scoped review at each independently verifiable checkpoint. Delegation, merges, publishing, and history rewriting are not automatic steps.

**Goal:** Give build verification to Gradle, useful JVM checks to Kotlin, native collector tooling to Go, and remove abandoned scripts and committed raw archive payloads.

**Architecture:** Reuse existing Gradle lifecycles, JUnit/TestKit, the Kotlin Tier E CLI, and the collector's Go module. Keep shell only at actual bootstrap, Git-hook, and container boundaries. Preserve useful behavioral checks; discard implementation-text assertions and test helpers whose purpose disappears with isolated fixtures.

**Tech stack:** Existing Gradle wrapper 9.6.1; JDK 25 build tooling with the library's JDK 22 floor; existing Kotlin/JUnit/TestKit and Go dependencies. No new external library, Gradle module, or runtime API.

**Spec:** The operator requirements and verified inventory in this document. Audit baseline: `96789e513136948338870ce7255fdec14e22c162`, checked in `.worktrees/pr531-master-resolution`. Implementation starts from the then-approved review branch, preserving unrelated work.

## Operator decisions

- Delete `lint.sh` and `check_coverage.sh`; do not replace them with wrappers.
- `./gradlew build` must fail on required lint, test, or coverage failure. Ordinary host checks should reuse valid Gradle caches.
- Remove unnecessary Python; move required JVM/build behavior to Kotlin tests/tasks. Use Go for the existing standalone Go collector's native tooling.
- Leave orchestrator and its dedicated control-plane launchers for their separate removal.
- Preserve `pom.xml` and RefactorFirst. The checked-in POM is operator-supported analysis metadata, not a deletion candidate. The current report runner additionally creates its own isolated POM.
- Replace the 13 experiment symlinks with physical checked-in files/directories.
- Keep meaningful compiler-rejection checks, named for their purpose rather than `retired`.
- Preserve historical evidence identities and move large archive bytes to durable storage.

## Verified inventory and corrections

| Area | Verified fact | Decision |
| --- | --- | --- |
| Root script tree | 70 tracked files; the removal/replacement set below contains 47 files and 4,030 existing lines | Reduce to 23 explicitly retained/deferred files; replacement code belongs to its owner |
| Python | 45 tracked files, 6,800 lines | Retire/migrate 38 files; keep the seven exploit-demo files for their separate payload/acceptance role |
| Gradle scanner | 512-line production lexer plus shell regression suite and 44 filesystem test methods | Port contracts, not a weaker regular-expression approximation |
| Consumer Python | Nine files, 1,012 lines | Preserve acceptance behavior in Kotlin; replace shared-worktree mutation with disposable fixtures |
| Native Python | Eight collector and nine experiment files, 1,295 lines | Move retained behavior into Go commands/tests |
| Negative API probes | 14 Java/Kotlin files, 81 lines total | Rename to `rejected-api`; keep distinct compiler probes |
| Experiment links | 13 links, all relative and inside the same checkout | Materialize copies; this is not a dependency on another Git worktree |
| Copy footprint | Current 13 targets contain 145 physical files, 452,652 bytes | Small explicit copy cost; exclude generated/untracked files |
| Raw archives | 79 archives: 51 `.tar.gz`, 28 `.tar.zst`; 235,535,341 bytes | Externalize original bytes with original SHA-256 values |
| Abandoned root files | Six agent patch fragments and root diagram, 255 lines | Delete; preserve POM |
| Hook installation | `installGitHooks` copies `scripts/git-audit-hook.sh`, not maintained `scripts/hooks/pre-commit` | Install the maintained source and remove the older duplicate |
| CI host gate | CI selects `unitCheck`; that currently selects tests/coverage, not the complete analyzer set | Make host-quality selection authoritative in Gradle |
| Legacy Tier E fixture | 558-line `_legacy.sh`; references are historical inventory/evidence, not active source callers | Retire to pinned Git replay, without another port |

The 4,030 lines are removed from the script tree, not promised net code savings: necessary checks are relocated. Archive deletion affects the current snapshot; historical Git objects remain until a separately approved history operation.

## Global constraints

- Preserve fail-closed containment, exact compiler diagnostics, native resource ownership, and external fixture review. No `EPERM`/`EACCES` bypasses or reduced test/coverage floors.
- Preserve candidate617 publication identities, all eight strict candidate locks, historical reports, and checksum ledgers. New executable inputs require new qualification and a new local candidate coordinate.
- Ordinary source/test/analyzer tasks remain cacheable where their inputs justify reuse. Native/kernel/container acceptance and measurements remain non-cacheable.
- Keep the process-wide baseline, Landlock-before-Seccomp order, FFM layouts, and public API unchanged.
- Do not modify credential handling or `GITHUB_TOKEN`.
- Preserve the main checkout's ten tracked modifications and untracked operator files. In particular, hook/indexer edits are user-owned; apply reviewed cleanup against the execution branch and reconcile those edits explicitly.
- During the active verification fleet, use its existing lock override. POSIX `flock` and Java `FileChannel.lock()` are different locking mechanisms; do not mix them and claim serialization.
- Retain original historical evidence bytes and README/checksum identities. Current migration guidance belongs in a new evidence index, not edits to checksum-bound historical documents.
- No new script registry, plugin framework, general migration framework, or duplicate verifier. The file lists here are the execution scope, not a new runtime configuration system.

## Execution preflight

- [ ] Inspect status, worktrees, and the current approved base. Preserve the main checkout and the PR531 resolution branch; this cleanup gets its own scoped backlog/review branch, not an expansion of PR531.
- [ ] Scaffold the cleanup backlog item with `./scripts/adkw new-issue --title "Unify build-owned verification and remove abandoned repository tooling"`, supply the exact target owners/files from this plan, and update its index through the normal project workflow.
- [ ] Use the orchestrator-managed assignment/review path to obtain an isolated cleanup worktree. Copy this operator-local plan into that workspace; keep its approved scope with the backlog item. No implementation in the dirty main checkout.
- [ ] If the approved base differs from `96789e51`, compare changes to the listed paths and revalidate affected callers/counts/receipts. Preserve newly owned files; a material scope change requires an updated plan.
- [ ] Establish the existing fleet lock override before executable verification. Record each task's changed input revision and acceptance logs; commit only a complete independently verified checkpoint.

```bash
git status --short --branch
git worktree list
git rev-parse HEAD
```

## Task 1: Make Gradle the complete quality gate and retire duplicate wrappers

**Modify:** `build.gradle.kts`, `buildSrc/build.gradle.kts`, `.github/workflows/ci.yml`, `buildSrc/src/test/java/io/mazewall/build/RootVerificationFunctionalTest.java`, `buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java`, `buildSrc/src/test/kotlin/io/mazewall/build/BuildLifecycleFunctionalTest.kt`, `scripts/tests/git_hook_environment_test.sh`, `README.md`, `docs/internals/designs/core/testing-strategy.md`.

**Create:** `buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt`, `buildSrc/src/main/kotlin/io/mazewall/build/CheckApprovedSuppressions.kt`, and their executable assertion suites under `buildSrc/src/verification/kotlin/io/mazewall/build/`.

**Delete after equivalent ownership:** `scripts/lint.sh`, `scripts/check_coverage.sh`, `scripts/check_ci_cache_policy.sh`, `scripts/check_gradle_lifecycle.sh`, `scripts/check_suppressions.sh`, `scripts/git-audit-hook.sh`, `scripts/run_tests.sh`.

**Interfaces:** Existing `unitCheck`, `kernelCheck`, `check`, `mergeCheck`, `build`, `verifyGradleReproducibility`, and `installGitHooks` remain the entrypoints. Add only the two typed checks replacing existing CI/suppression checks; they throw `GradleException` on failure and declare their source/config inputs.

- [ ] Extend the existing TestKit lifecycle fixtures to inject ktlint, Detekt, SpotBugs, test, and coverage failures separately. Assert that `unitCheck`/`build` fail, and that host verification selects no kernel test task. Preserve actual analyzer execution tests, not just task-name presence.
- [ ] Make `unitCheck` select the existing enabled host analyzers and warning ratchet as well as host tests/coverage. Select lazy task collections from participating source owners; keep existing demo/tool exemptions. Include root host tests. Avoid new hardcoded module membership and eager configuration resolution.

```kotlin
// Add to the existing root host-quality wiring; children remain lazy task collections.
val hostAnalyzers = setOf("ktlintCheck", "detektMain", "spotbugsMain")
tasks.named("unitCheck") {
    dependsOn(tasks.named("test"))
    dependsOn(subprojects.filterNot { it.path.startsWith(":demos") }.map { owner ->
        owner.tasks.matching { it.name in hostAnalyzers }
    })
    dependsOn(tasks.matching { it.name in hostAnalyzers })
    dependsOn("detektWarningRatchet", "checkCiCachePolicy", "checkApprovedSuppressions",
        "checkGradleLazyResolution", "validateBacklogIndex")
}
```

- [ ] Keep Gradle's existing host floors: enforcer 82%, profiler 70%, platform 65%. Preserve the separately configured native floors and ABI/package/dependency checks. Remove the stale class-specific shell parser.
- [ ] Port the five CI cache-policy conditions and the existing suppression allowlist/rationale contract into their typed tasks. Regression cases: accepted configuration, missing required setting, forbidden cache setting, unapproved suppression, approved suppression with/without rationale, and multiline suppression. Preserve the existing contract without claiming a new compiler-level security analysis.
- [ ] Extend the existing custom `verification` source set with `src/verification/kotlin`, compiling against main class-directory outputs rather than the buildSrc JAR. Supply class-directory friend paths for package-internal Kotlin contracts instead of widening their production visibility. Assertion objects expose only the `@JvmStatic fun run()` interoperability entry required by `VerificationActionsTest`. Invoke both suites from that mandatory Java harness. Confirm `verificationActionsTest` still runs before `jar` and `compileVerificationKotlin` has no `jar` dependency; do not reintroduce the previously fixed Kotlin-test/JAR cycle.
- [ ] Attach those checks to host/full verification. Remove the separate CI shell steps once the host gate owns them. Keep host verification before container setup and the kernel phase separate.
- [ ] Change `installGitHooks` to copy `scripts/hooks/pre-commit`. Retain its net-new-pattern semantics, operator allowlist, staged Git context, child Git-context sanitization, and Portal verification. Test the installed hook bytes and executable permission in an owned temporary Git repository. Do not replace the live operator hook as a planning step.
- [ ] Update live guides and hook messages to use `./gradlew build`, `./gradlew unitCheck`, `./gradlew kernelCheck`, or the retained container runner as appropriate. Historical records retain their original commands.
- [ ] Delete the seven files listed above after their callers and checks are migrated.

**Focused checks:**

```bash
./gradlew -p buildSrc verificationActionsTest
./gradlew -p buildSrc test --tests '*BuildLifecycleFunctionalTest*'
bash scripts/tests/git_hook_environment_test.sh
./gradlew unitCheck --dry-run
```

**Accept:** Required lint/coverage failures fail Gradle; host CI actually selects analyzers; warm host verification can reuse unchanged inputs; hook installation uses the maintained hook; normal build does not install hooks, format sources, regenerate diagrams, or run RefactorFirst.

## Task 2: Move the Gradle source guard and its real regressions into build logic

**Create:** `buildSrc/src/main/kotlin/io/mazewall/build/GradleSourceGuard.kt`, `GradleSourceInventory.kt`, `CheckGradleLazyResolution.kt`; `buildSrc/src/verification/kotlin/io/mazewall/build/GradleSourceGuardTest.kt`, `GradleSourceInventoryTest.kt`.

**Modify:** `build.gradle.kts:checkGradleLazyResolution`, `buildSrc/build.gradle.kts`, `buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java`, `docs/internals/designs/core/testing-strategy.md`.

**Delete after parity:** `scripts/gradle_lazy_resolution.py`, `scripts/check_gradle_lazy_resolution.sh`, `scripts/tests/gradle_lazy_resolution_test.sh`, `scripts/tests/gradle_source_discovery_test.py`.

**Interfaces:** Package-internal `GradleSourceGuard.check(source: String, groovy: Boolean): List<Violation>` with `Violation(line: Int, text: String)`; an IO-owned inventory over a supplied checkout root; the existing task name `checkGradleLazyResolution`.

- [ ] Transfer the shell fixtures into parameterized Kotlin tests. Expected failures compare the complete file/line/text diagnostic list. Keep the historical eager Java/Spring regressions and positive action/provider cases.
- [ ] Transfer all 44 filesystem test methods as cases in the Kotlin inventory suite. Preserve real Git indexes, linked worktrees, inherited alternate indexes, tracked ignored sources, untracked source discovery, directory aliases, symlink-before-`..`, cycles, external/private targets, permissions, and fail-closed enumeration/read errors.
- [ ] Port the bounded lexer and decision logic using Kotlin/JDK facilities. Preserve nested strings/comments/interpolation, immediately consumed closures/providers, execution-body admission, and the documented limits. No new regex-only approximation or filesystem exclusions.
- [ ] Keep inventory effects separate from pure source decisions. Clear only Git-repository-local variables for read-only Git children; retain all other environment. Keep explicit cache/private pruning and the 40-link bound.
- [ ] Replace the `Exec` wrapper with the typed task; wire it into host/full verification. Declare source/configuration inputs; do not cache an inventory result while ignoring topology or discovery inputs.
- [ ] Invoke the two executable Kotlin assertion suites from the mandatory `VerificationActionsTest` harness using Task 1's verification compilation. Do not assume a standalone `buildSrc:test` task is selected by ordinary root builds. Keep the no-JAR compilation dependency verified and the main-class test suites out of the produced buildSrc JAR.
- [ ] Remove the four source/test wrappers after parity passes.

```bash
./gradlew -p buildSrc verificationActionsTest
./gradlew checkGradleLazyResolution
```

**Accept:** No Python or standalone shell test is needed for the source guard; all existing positive/negative cases retain their decision and diagnostic meaning. Permission cases run under a genuinely unprivileged UID.

## Task 3: Delete abandoned agent/tooling machinery rather than translating it

**Delete:**

```text
scripts/audit_negative_memory.py
scripts/auto_memory_capture.py
scripts/backlog_negative_indexer.py
scripts/bootstrap_agent_memory.py
scripts/decompose_work_package.py
scripts/error_memory_capture.py
scripts/pre_invocation_memory.py
scripts/pre_invocation_sync.py
scripts/check_backlog_resolved.sh
scripts/export_architecture_graph.sh
scripts/file_structure.main.kts
scripts/validate_review_evidence.py
fix_bypass_tests.patch
fix_test_fallback.patch
fix_test_imports.patch
update_test.patch
use_engine.patch
use_engine_landlock.patch
class_diagram.puml
```

**Modify:** `scripts/git_async_indexer.sh`, `.agents/hooks.json`, `.grok/hooks/mazewall_guards.json`, `.agents/skills/file_structure/SKILL.md`, `docs/internals/documentation-standards.md`, `enforcer/README.md`, `profiler/README.md`, `docs/internals/designs/core/architectural-map.md`, `docs/internals/designs/core/review-hardening-verification.md`, `docs/internals/designs/profiler/tier-e-legacy-script-retirement-20260929.md`.

- [ ] Remove custom memory-hook invocations from both tracked hook configurations. Delete an empty hook configuration when it has no other role. Keep persistent memory through the already configured agent tools; do not add a Kotlin memory daemon or rewrite users' global memory stores.
- [ ] Reduce `git_async_indexer.sh` to its actual retained indexing role. Remove inline Python writing `~/.agentmemory/standalone.json` and the erroneous `/scripts/backlog_negative_indexer.py` branch. Preserve/reconcile the operator's uncommitted indexer improvements rather than overwriting the file.
- [ ] Replace live outline guidance with the existing `./scripts/adkw slice` owner. Remove the unused graphify launcher and speculative graph installation guidance; keep canonical architectural docs and generated diagrams.
- [ ] Treat the 30+30-row review checker as historical report-replay tooling: record pinned replay from `96789e51` in its current guidance, then remove the bespoke HEAD script. Its shallow-history-sensitive commit checks are not a new mandatory ordinary CI gate.
- [ ] Remove the six unreferenced patch fragments and obsolete root diagram. Preserve `pom.xml`, RefactorFirst tasks, runner, and runner test.
- [ ] Sweep executable/config/live-guide references. Keep historical terminal logs, source-state JSON, checksum-bound READMEs, and resolved issue evidence unchanged.

```bash
bash -n scripts/git_async_indexer.sh
bash scripts/tests/refactor_first_runner_test.sh
./scripts/adkw check-backlog
git diff --check
```

**Accept:** No product workflow invokes the deleted memory scripts; no memory data is deleted; no live guide points to removed analysis helpers; orchestrator-specific launchers and module code are untouched.

## Task 4: Put packaged-consumer checks and their lock under Kotlin/Gradle ownership

**Create:** `src/consumerVerification/kotlin/io/mazewall/verification/PackagedConsumerMatrixTest.kt`, `SharedVerificationLock.kt`, `SharedVerificationLockTest.kt`, `IntegrationBoundaryEvidenceTest.kt`.

**Modify:** `build.gradle.kts`, `tests/consumers/java-api/build.gradle`, `tests/consumers/kotlin-api/build.gradle`, `tests/consumers/java-api/src/main/java/consumer/VerifyCompilation.java`, `tests/consumers/README.md`, `benchmarks/integration-boundaries/README.md`, `docs/internals/reviews/integration-compatibility-manifest.md`.

**Move:** both language trees from `src/retired/` to `src/rejected-api/`.

**Delete after parity:** `scripts/check_integration_consumers.sh`, `benchmarks/integration-boundaries/check_raw.py`.

**Interfaces:** An explicit root `consumerCheck` Test task over the new source set, using `consumerRepository`, `consumerVersion`, and `consumerKotlinVersion`; the independent consumer's task becomes `verifyRejectedApi`. Add explicit `verifyIntegrationBoundaryEvidence` selection with `boundaryRaw` and `boundaryCustody` inputs. Reuse installed JUnit/JSON dependencies through existing test runtime configurations and shared test output; do not add a module or external dependency.

- [ ] Keep consumer verification explicit: normal `build` verifies its harness regressions without demanding an operator-provided Maven repository or launching every framework deployment.
- [ ] Port the matrix to a Kotlin parameterized test with JDK22 and JDK25 rows. Invoke the real independent build; require exact published JAR/POM/module files, actual artifact hashes, stable Kotlin 2.4.20 by default, exact resolved Maven paths, and rejection of source-project substitution.
- [ ] Preserve real clean compiler execution for each row and each diagnostic fixture. Host source/analyzer reuse is allowed; cached compilation must not be presented as a newly executed diagnostic probe.
- [ ] Rename the 14 probes and `verifyRetiredApi` to `verifyRejectedApi`. They are 81 lines proving removed wrapper/factory APIs remain unavailable, not retired production code. Keep the two different `wrap` calls, factory signatures, type probe, and both languages separate so one unrelated compiler error cannot satisfy all checks. Preserve exact error code, symbol, source, and error-count assertions; keep scope-boundary probes as well.
- [ ] Resolve the default shared lock under the absolute Git common directory: `mazewall/verification.lock`. Accept `verificationLock` as an explicit override for the current fleet. Create owned metadata parents on a new checkout and use the same `flock` mechanism for all migrated consumers. Hold the lease across the whole matrix; use bounded acquisition and unconditional release. Do not substitute Java record locks for `flock`.
- [ ] Lock regression cases use real temporary normal/linked Git repositories: equal common-dir lock, missing session directories, independent repositories, two competing processes, and release on failure/timeout. Keep the fleet override until coordinated retirement.
- [ ] Port `check_raw.py`'s bounded TSV schema, accounting, IDs, content hashes, timestamps, resource/physical-end checks, attribution limits, and all non-no-op corruption cases. Preserve its statement that observation validity is distinct from artifact authenticity; never promote this checking smoke into a performance claim.
- [ ] Update fresh staging/lock-generation guidance. Generate new strict locks only for the new cleanup candidate coordinate. Do not rewrite historical/candidate617 locks to make the new source pass.

```bash
./gradlew consumerCheck -PconsumerRepository="$repository" -PconsumerVersion="$version" -PconsumerKotlinVersion=2.4.20
./gradlew verifyIntegrationBoundaryEvidence -PboundaryRaw="$raw" -PboundaryCustody="$custody"
```

The repository/version variables are created by the existing local publication staging flow in `tests/consumers/README.md`; raw/custody are the actual paired observation and custody files named by `benchmarks/integration-boundaries/README.md`. They are required operator inputs, not fabricated fixtures.

**Accept:** The independent real-publication matrix passes both JDK rows; every rejection probe fails for its intended reason; the source guard admits the new build wiring; no dated session directory or Python executable is required.

## Task 5: Replace the consumer Python harnesses with Kotlin behavioral tests

**Create under** `src/consumerVerification/kotlin/io/mazewall/verification/`: `KoogBootstrapTest.kt`, `KoogWorkerPackageTest.kt`, `SpringBootPackagedAcceptanceTest.kt`, `SpringBootMeasurementOwnershipTest.kt`, `ConsumerProcessEvidenceTest.kt`, and a narrowly owned fixture/process helper only where existing helpers cannot supply the required behavior.

**Modify:** `build.gradle.kts`, `tests/consumers/koog-portal/README.md`, `tests/consumers/spring-boot/README.md`, `demos/spring-boot-sandbox/README.md`.

**Delete after the mapped behaviors pass:**

```text
tests/consumers/koog-portal/bootstrap-test.py
tests/consumers/koog-portal/check-worker-package.py
tests/consumers/koog-portal/fixture-files.py
tests/consumers/koog-portal/fixture-ownership-test.py
tests/consumers/koog-portal/producer-exclusion-test.py
tests/consumers/spring-boot/fixture_io.py
tests/consumers/spring-boot/test_fixture_io.py
tests/consumers/spring-boot/test_measurement.py
tests/consumers/spring-boot/verify.py
```

**Interfaces:** `consumerBootstrapCheck` for real isolated bootstrap/packaging tests; `consumerNativeCheck` for selected finished application acceptance. Forward `verificationLock`, `consumerRepository`, `consumerVersion`, `consumerRevision`, `consumerKoogJar`, `consumerBootJar`, and fresh `consumerEvidence` where the selected test needs them. Require relevant inputs on selected tasks, not during unrelated build configuration.

- [ ] Use disposable repository clones/fixtures for bootstrap tests. Stop mutating real checkout sources and receipts; this removes the need to translate the 117-line borrow/restore helper and its helper-only tests. Assert the original checkout is unchanged even when a producer build fails.
- [ ] Keep real producer add/modify/delete, receipt version/root/missing checks, excluded `compileKotlin`/`jar` forms, actual `onlyIf` skipping, and unchanged stale receipt rejection. Keep `scripts/koog-build.sh`: it bootstraps the plugin before Gradle configuration, an actual shell boundary.
- [ ] Check actual opaque host/worker packaging with JDK ZIP/JAR and SHA-256: service inventory, format2 metadata, bundle digest, expected worker classes, fixture-dependent denied probe, no host worker classes, and no Koog/Spring dependencies inside the worker.
- [ ] Port Boot acceptance as an external Kotlin fixture reviewer launching the actual finished executable with `java -jar`. Require all four nested publication bytes, expected Boot launcher/version, exact producer/demo/harness identities, and independently written fixture review. The application still cannot approve itself.
- [ ] Preserve missing/stale review, mutated whole JAR/template, unsupported required annotation, and all advertised native markers/effects: required binding, trusted initialization, virtual caller/platform body, JDBC commit, filesystem denials, and physical worker retirement.
- [ ] Preserve all 14 finished-JAR measurement collision/alias negatives. Use a fresh private deployment for each mutation case instead of a general shared-file restoration framework; preserve unexpected files and evidence.
- [ ] Reuse the existing isolated-process lifecycle implementation where compatible. Its `runIsolatedTest` assumes a classpath main and success; do not use it unchanged for expected-failure `java -jar` invocations. Any narrow adaptation must retain genuine child ownership, pre-launch argv/cwd recording, separate partial streams, timeout-before-signal state, bounded terminate/kill/reap, failure identity, and unrelated-process survival. Keep these as real process tests.
- [ ] Delete the Python files and dead Python-helper tests once these production/acceptance contracts pass. Preserve immutable old evidence; obtain new evidence for the new Kotlin harness/source identity.

```bash
./gradlew consumerBootstrapCheck
./gradlew consumerNativeCheck -PconsumerRepository="$repository" -PconsumerVersion="$version" -PconsumerRevision="$revision" -PconsumerKoogJar="$koogJar" -PconsumerBootJar="$bootJar" -PconsumerEvidence="$evidence"
```

**Accept:** Real optional-package bootstrap and native finished-JAR checks pass through Gradle/Kotlin, using the shared lock and fresh evidence. Required native cases are not silently skipped for unavailable permissions or fixtures.

## Task 6: Materialize the native experiment snapshot

**Replace these tracked symlinks with physical tracked content:**

```text
experiments/hotspot-agentless/cmd/mw-gadget-build
experiments/hotspot-agentless/cmd/mw-hotspot-demo
experiments/hotspot-agentless/dependencies.lock.json
experiments/hotspot-agentless/gadgets
experiments/hotspot-agentless/go.mod
experiments/hotspot-agentless/go.sum
experiments/hotspot-agentless/internal/capture
experiments/hotspot-agentless/internal/identity
experiments/hotspot-agentless/internal/model
experiments/hotspot-agentless/internal/output
experiments/hotspot-agentless/internal/preflight
experiments/hotspot-agentless/internal/quality
experiments/hotspot-agentless/patches
```

**Modify:** `experiments/hotspot-agentless/Makefile`, `experiments/hotspot-agentless/scripts/prepare-gadget.py` as an intermediate local driver, `experiments/hotspot-agentless/README.md`.

**Copy additional required local preparation boundary:** `profiler/hotspot-agentless/scripts/rebuild-profiler-ebpf.sh` into the experiment's `scripts/` directory. Record the copied source revision/hash in `experiments/hotspot-agentless/source-snapshot.json`.

- [ ] Export only committed content from the approved execution revision to an owned temporary directory. Materialize the 13 target mappings from that export; never copy live `build`, dependency/module caches, untracked files, or another worktree's output.
- [ ] Verify every copied file's bytes and Git executable mode against its source. Keep the existing Go module/import namespace, pins, patch order, native flags, and source fingerprint semantics.
- [ ] Make experiment dependency preparation/build/container-build operate on its local copied tree. Remove delegation into `../../profiler/hotspot-agentless`, including generated module replacement paths. The canonical collector remains the production owner; the experiment is an explicit source snapshot, not silently claimed equivalent to future production edits.
- [ ] Record deliberate snapshot updates with their source/hash identity. Do not add a synchronization daemon or code-generation layer.
- [ ] Prove portability from an exported copy of only the experiment directory: no symlinks, parent collector directory, `.worktrees`, or generated dependency paths from the original checkout. Prepare dependencies, compile, and run the same host Go tests there.

```bash
make -C experiments/hotspot-agentless verify
```

**Accept:** Zero source symlinks remain; the experiment builds from its own physical snapshot; copies have known provenance; runtime/patch semantics are unchanged. Existing absolute historical result paths remain historical evidence only.

## Task 7: Give native preparation, assets and benchmark helpers to Go

**Create:** `profiler/hotspot-agentless/cmd/mw-collector-tools/main.go`, focused packages/tests under `profiler/hotspot-agentless/internal/tooling/`; matching committed tool sources in the standalone experiment snapshot where it needs preparation; experiment-owned benchmark/candidate code/tests under `experiments/hotspot-agentless/internal/benchmark/` and `internal/candidate/`, with a small `cmd/mw-experiment-tools/main.go`.

**Modify:** `profiler/build.gradle.kts:buildHotSpotAssets/verifyHotSpotAssets/distHotSpotAssets/installHotSpotAssets`, both native `Makefile`s and README files, current native scripts that call Python including `experiments/hotspot-agentless/scripts/run-benchmark.sh`, and the live target references in `docs/internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md`. Preserve the explicit opt-in nature of native asset tasks; ordinary JVM builds must not acquire a new Go/Docker prerequisite.

**Delete after equivalent retained behaviors pass:**

```text
profiler/hotspot-agentless/scripts/assets.py
profiler/hotspot-agentless/scripts/distribution.py
profiler/hotspot-agentless/scripts/kernel-digest.py
profiler/hotspot-agentless/scripts/prepare-gadget.py
profiler/hotspot-agentless/scripts/test-assets.py
profiler/hotspot-agentless/scripts/test-distribution.py
profiler/hotspot-agentless/scripts/test-kernel-digest.py
profiler/hotspot-agentless/scripts/test-release-footprint.py
experiments/hotspot-agentless/scripts/prepare-gadget.py
experiments/hotspot-agentless/scripts/run-benchmark.py
experiments/hotspot-agentless/scripts/run-capability-candidates.py
experiments/hotspot-agentless/scripts/test-run-benchmark.py
experiments/hotspot-agentless/scripts/verify-loom-candidate.py
experiments/hotspot-agentless/scripts/verify-runtime-candidate.py
experiments/hotspot-agentless/tests/unit/test-capability-readiness.py
experiments/hotspot-agentless/tests/unit/test-loom-candidate.py
experiments/hotspot-agentless/tests/unit/test-runtime-candidate.py
```

- [ ] Use Go's existing standard packages for JSON, hashes, filesystem/ZIP/tar handling, process execution, and tests. Do not make a standalone Go collector require a Kotlin/Gradle JVM to prepare itself.
- [ ] Move lock/image extraction and module preparation into the native command: preserve pinned module checksums, complete ordered patch/addition/rebuild digest, `--fuzz=0`, generated-only dependency writes, artifact hashes, incompatible mode rejection, and incomplete/provenance mismatch refusal. Keep module caches untouched.
- [ ] Port asset/distribution validation before deleting Python: real bundle bytes, safe member/path admission, manifest/artifact digest mismatch, publication/install ownership, expected modes, and release-footprint checks. Replace the release checker’s hardcoded historical patch hash with the actual verified preparation identity; retain stripped ELF/static executable and Go build provenance checks.
- [ ] Retire the uncalled AF_ALG qualification prototype `kernel-digest.py` with its paired self-test to pinned historical replay at `96789e51`. It is not a production hash provider. Do not add a new Go AF_ALG feature merely to translate this old diagnostic; preserve actual production hash/admission contracts and their existing tests.
- [ ] Replace the four existing Gradle asset tasks' Python commands with the built native tooling command. Keep verify/install read-only with respect to source and independent from native construction; retain explicit new install destinations and source/notices packaging. Make the Go tool build a declared dependency with its real source/pin inputs, not an undocumented binary presumed present.
- [ ] Move inline Make/shell Python as well as `.py` files: pinned image reads, patch IDs, OCI digest lookup, and random experiment run tokens. Preserve digest-selected OCI references; no mutable-tag fallback.
- [ ] Port retained benchmark/candidate decisions and their negative tests. Remove helper-only Python test scaffolding. Keep precise unavailable/readiness/verdict labels and native acquisition external state.
- [ ] Run canonical and snapshot Go unit/race tests. Run owning packaging and kernel checks when their changed driver is responsible for acquisition; keep those as explicit native checks, not cached protection evidence.

```bash
make -C profiler/hotspot-agentless unit
make -C experiments/hotspot-agentless verify
make -C experiments/hotspot-agentless preflight-test
```

**Accept:** Both native trees' active tooling uses Go plus necessary OS commands, with no `.py` or inline Python call left. The experiment still works standalone after removal of the canonical tree from its exported fixture.

## Task 8: Replace Tier E script proliferation with the Gradle-generated Kotlin CLI

**Modify:** `profiler/build.gradle.kts`, `TierETrustworthyRunner.kt`, `TierETrustworthySourceState.kt`, existing Tier E bundle/launcher tests, `.github/workflows/ci.yml`, live Tier E guides and launch commands. Kotlin paths are under `profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/` and `profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/`.

**Delete:** the Tier E files in Appendix A, `TierEActiveEntrypointPolicy.kt`, and `TierEActiveEntrypointPolicyTest.kt`; replace shell-specific bootstrap/profile tests with installed-launcher behavior tests.

**Interfaces:** Keep `TierEToolsMainKt` and its existing Kotlin subcommands. Add a Gradle `CreateStartScripts` output named `tier-e-tools` to the existing `installDist`. Existing explicit short/long Gradle benchmark tasks retain their semantics. No new generic task/plugin framework.

- [ ] Generate the launcher with the existing tooling/runtime classpath. The normal bootstrap is:

```bash
./gradlew :profiler:installDist
./profiler/build/install/tier-e-daemon/bin/tier-e-tools rootful-gate-profile "$PWD" "$PWD/build/tier-e-evidence" "$PWD/config/testing/tier-e/rootful-gate.json"
```

- [ ] Translate the current launcher arguments/default profiles to the same Kotlin subcommands, including explicit legacy-environment replay selection where supported. Update current callers and guides; historical command records stay pinned.
- [ ] Update CI's rootful job to the generated launcher after `installDist`, with unchanged fresh artifact/endpoint requirements.
- [ ] Update the builder container in `TierETrustworthyRunner`: it currently calls `scripts/build_tier_e_benchmark_bundle.sh`. Preserve cold bootstrap inside the selected image/cache, then execute the generated Kotlin launcher with `bundle-build`. Keep build preparation outside an enclosing same-checkout JavaExec invocation that would recursively bootstrap Gradle against its own project cache. Preserve prepared-native-package identity verification and actual builder exit status.
- [ ] Keep the builder's native/runtime cache ownership, fingerprints, staged publication and refusal to overwrite verified prior bundles. Update source-state inputs to the real Gradle/Kotlin owners.
- [ ] Port only uncovered useful shell-test cases: unchanged input yields the same fingerprint; changed source/mode changes identity; wrong fingerprint, changed/missing runtime, symlinked artifact and failed bootstrap are rejected. Extend existing `TierEBenchmarkBundleFingerprintTest`, `ManifestTest`, and `BuilderTest`; do not duplicate their existing cases.
- [ ] Remove obsolete shell-text assertions, launcher-file whitelist policy, and fake-shell tests once replaced with generated CLI/profile behavior. Keep calibration/witness/terminal-state assertions in their existing actual Kotlin evidence tests.
- [ ] Retire the unused 558-line `_legacy.sh` and its historical references to pinned replay at `96789e51`; do not implement another controller beside `TierESplitFixtureController`.
- [ ] Delete the 23 Tier E script-tree files listed in Appendix A after the builder and current callers move.

```bash
./gradlew :profiler:test --tests '*TierEBenchmarkBundle*' --tests '*TierETrustworthy*' --tests '*TierEInstalledLauncher*'
./gradlew :profiler:installDist
./profiler/build/install/tier-e-daemon/bin/tier-e-tools version
```

**Accept:** The installed CLI works from the real distribution; cold bootstrap failure propagates; bundle reuse/identity/negative cases pass; the rootful gate uses current actual inputs; deleted script names have no active caller. No prior native qualification is attributed to this new source revision.

## Task 9: Externalize raw archives with a working restoration path

**Create:** `docs/internals/evidence/archives.tsv`, `docs/internals/evidence/ARCHIVES.md`, `buildSrc/src/main/kotlin/io/mazewall/build/FetchHistoricalEvidence.kt`, and its test.

**Modify:** `build.gradle.kts`, `.gitignore`; remove the 79 tracked archive payloads only after verified publication/retrieval. Preserve their original reports, README bytes, SHA256SUMS, source/acceptance manifests, and member indexes.

**Destination proposed by this plan:** a dedicated durable GitHub Release on `Pilleo/mazewall`, tag `historical-evidence-2026-10-14`. Asset names are the archive path relative to `docs/internals/evidence/` with `/` replaced by `__`; this disambiguates repeated names. Publishing occurs through the approved operator/review path, not during planning.

**Manifest contract:** sorted UTF-8 TSV rows with `path`, `bytes`, `sha256`, `url`. Every path is an existing original archive path; all 79 rows must account for exactly 235,535,341 bytes at the audit baseline. Generate values from committed blob bytes, not altered working copies. The exact URL prefix is `https://github.com/Pilleo/mazewall/releases/download/historical-evidence-2026-10-14/`.

- [ ] Inventory all archives and verify their existing recorded hashes before upload. Preserve and report any pre-existing ledger mismatch rather than changing its checksum to make the move pass.
- [ ] Upload the original bytes with collision-free names. Download every asset into a different owned temporary directory; check exact length and SHA-256 for all 79. Check tar member indexes where recorded and replay the two long-matched acceptance datasets using their approved historical checkout.
- [ ] Add `fetchHistoricalEvidence`, selecting a required dataset directory with `-PevidenceDataset`. Restore original archive filenames for the saved historical commands. Permit only committed manifest paths under the evidence root; stream to an exclusive temporary file; enforce expected byte bound/hash; publish only verified complete bytes. Existing matching files may be reused; conflicting files are preserved and fail. Do not fetch during ordinary build.
- [ ] Tests use a local HTTP fixture: success, changed bytes, truncated/oversized response, unavailable asset, invalid/escaping path, and existing conflicting file. The tests establish actual restoration behavior, not a test that merely counts manifest rows.
- [ ] Keep original checksum ledgers unchanged. A verified archive restored at its original name must make the original directory's `sha256sum -c` work, including its historical pinned-script replay procedure.
- [ ] After every remote retrieval passes, remove exactly those 79 tracked blobs. Add evidence-root-anchored ignores for restored `.tar.gz`/`.tar.zst`; keep small test-consumed JSON/TSV and integrity indexes committed.
- [ ] Record upload/download/member/replay evidence and the new checkout size. Do not rewrite Git history in this change. If publication is unavailable, keep the payloads and report Task 9 incomplete; the other cleanup tasks remain independently verifiable.

```bash
./gradlew fetchHistoricalEvidence -PevidenceDataset=tier-e-long-matched-controls-corrected-20260925
```

**Accept:** All archives have independently verified durable retrieval; saved integrity/replay commands work after restoration; normal checkout has no tracked raw archive payloads; original evidence identities remain valid.

## Completion checkpoint

- [ ] One scoped connected-code review per task, including its callers and relevant fixture/build wiring. No repeated full reviews of the same unchanged revision.
- [ ] Verify the 47-file script removal set, the 23-file retained set, zero tracked experiment symlinks, zero active Python in build/consumer/native tooling, and preservation of the seven exploit-demo Python files. Check executable/config references and dynamic caller patterns, not just filename absence.
- [ ] Run already-existing focused tests for retained boundaries when modified: CET cache fixture, Git hook environment, RefactorFirst runner. Do not write deletion-only tests for obsolete wrappers or patch fragments.
- [ ] Confirm `pom.xml`/RefactorFirst and orchestrator-specific files have no cleanup diff; public API/ABI and candidate617 identities/locks remain unchanged.
- [ ] Confirm ordinary build does not mutate tracked files and the host gate can reuse valid cache state. Native and external acceptance still execute when selected.
- [ ] After focused checks pass, run the required full included-module build once for the final executable revision:

```bash
./gradlew build
./scripts/adkw check-backlog
git diff --check
```

- [ ] Keep native Go/consumer acceptance evidence from their owning checkpoints; rerun only for subsequent changes affecting those inputs or a concrete unresolved failure. Do not claim default Gradle build alone covered optional external application/native workflows.
- [ ] Enter through a scoped backlog/review path; stage only the change's files. Do not merge, force-push, publish ordinary library artifacts, or mutate the default branch automatically.

## Appendix A: Exact root script disposition

### Retain 18 files with actual current roles

```text
scripts/adk-codanna-reindex
scripts/adk-wrapper.properties
scripts/adkw
scripts/cet_fixture_fingerprint.sh
scripts/code_atlas.sh
scripts/git_async_indexer.sh
scripts/hooks/pre-commit
scripts/koog-build.sh
scripts/measure_gradle.sh
scripts/refactor_first.sh
scripts/repeat_supervisor_ack.init.gradle
scripts/run_cet_demo.sh
scripts/run_containerized_tests.sh
scripts/run_vulnerable_app_demo.sh
scripts/sg.sh
scripts/tests/cet_fixture_cache_test.sh
scripts/tests/git_hook_environment_test.sh
scripts/tests/refactor_first_runner_test.sh
```

These are bootstrap/container/Git/native/manual-measurement boundaries, existing operator-tool entrypoints, or their meaningful regressions. Retained Python is only the seven files under `exploits/`, which implement/test the deliberately vulnerable application's independent HTTP/payload/exploitation scenarios; they are not JVM build or consumer verification infrastructure.

### Defer five orchestrator/control-plane files unchanged

```text
scripts/new_backlog_issue.sh
scripts/paperclip_backlog_sync.kts
scripts/run_orchestrator.sh
scripts/run_paperclip_loop.sh
scripts/sync_backlog_paperclip.sh
```

### Remove/replace exactly 47 files

```text
scripts/audit_negative_memory.py
scripts/auto_memory_capture.py
scripts/backlog_negative_indexer.py
scripts/bootstrap_agent_memory.py
scripts/build_tier_e_benchmark_bundle.sh
scripts/check_backlog_resolved.sh
scripts/check_ci_cache_policy.sh
scripts/check_coverage.sh
scripts/check_gradle_lazy_resolution.sh
scripts/check_gradle_lifecycle.sh
scripts/check_integration_consumers.sh
scripts/check_suppressions.sh
scripts/check_tier_e_benchmark.sh
scripts/compare_tier_e_stack_transports.sh
scripts/decompose_work_package.py
scripts/error_memory_capture.py
scripts/export_architecture_graph.sh
scripts/file_structure.main.kts
scripts/git-audit-hook.sh
scripts/gradle_lazy_resolution.py
scripts/lint.sh
scripts/pre_invocation_memory.py
scripts/pre_invocation_sync.py
scripts/run_tests.sh
scripts/run_tier_e_checks.sh
scripts/run_tier_e_coverage_benchmark.sh
scripts/run_tier_e_decoder_microbenchmark.sh
scripts/run_tier_e_hybrid_fault_matrix.sh
scripts/run_tier_e_hybrid_live_comparison.sh
scripts/run_tier_e_mapped_drain_microbenchmark.sh
scripts/run_tier_e_matched_flavour_benchmark.sh
scripts/run_tier_e_node_noise_probe.sh
scripts/run_tier_e_release_benchmark.sh
scripts/run_tier_e_rootful_gate.sh
scripts/run_tier_e_split_coverage_matrix.sh
scripts/run_tier_e_split_matched_benchmark.sh
scripts/run_tier_e_split_negative_matrix.sh
scripts/run_tier_e_split_privilege_fixture.sh
scripts/run_tier_e_split_privilege_fixture_legacy.sh
scripts/run_tier_e_sustained_capacity.sh
scripts/run_tier_e_trustworthy_benchmark.sh
scripts/test_tier_e_benchmark_bundle.sh
scripts/tests/gradle_lazy_resolution_test.sh
scripts/tests/gradle_source_discovery_test.py
scripts/tier_e_benchmark_source_state.sh
scripts/tier_e_tools
scripts/validate_review_evidence.py
```

## Plan verification evidence

- Audit worktree remained clean at `96789e51`; no implementation change was made.
- `lint.sh`/coverage fake-Gradle checks reproduced failed32 -> success0; real reports confirmed both stale class selectors.
- Fresh temporary Git checkout reproduced the consumer's dated lock-directory failure before Gradle execution.
- Full tracked-file/caller inventories established 70 script files, 45 Python files/6,800 lines, 14 negative API probes/81 lines, and 13 within-checkout links.
- Current archive inventory established 79 archives/235,535,341 bytes and repeated basenames requiring unique external asset names.
- Existing ADK backlog validation passed for the audit snapshot. Orchestrator work is deliberately excluded from this plan by operator instruction.

Execution success is established by the task acceptance gates above; this document does not claim unimplemented migrations, archive publication, or native requalification have passed.
