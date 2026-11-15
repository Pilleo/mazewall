# PR 567 build-fixture corrections: focused verification status

**Status (2026-11-15): the focused local fixtures, private kernel lane, and current offline full quality gate pass; PR 567 run 43669767931 remains red and fresh required remote CI is pending.** The bounded changes address the enforcer configuration-cache capture, offline fixture setup, and kernel-lane execution envelope. These are build/test-fixture and CI changes; they do not change production runtime or sandbox behavior. Issues 858 and 787 remain OPEN.

See [issue 858](../backlog/issue-20261115-003718-858-make-enforcer-functional-test-fixtures-configurati.md) and [PR 567](https://github.com/Pilleo/mazewall/pull/567). The paused Tier E handoff links here only for current repository/CI status; this does not alter its accepted evidence or security boundary.

## Changes in scope

- [`enforcer/build.gradle.kts`](../../../enforcer/build.gradle.kts) now supplies the seven-role test JVM classpath through a managed argument provider instead of resolving project/configuration state from the task execution closure. The seven original roles, canonical paths, distinct artifact selection, task dependencies, and opt-in exclusions remain.
- [`ManagedFixtureClasspathArgumentProvider.java`](../../../buildSrc/src/main/java/io/mazewall/build/ManagedFixtureClasspathArgumentProvider.java) models the three generated JAR inputs and runtime files as Gradle-managed inputs. Runtime files use absolute path sensitivity to match the emitted canonical paths. It does not capture the script `Project` in an execution callback.
- [`buildSrc/build.gradle.kts`](../../../buildSrc/build.gradle.kts) and [`RootVerificationFunctionalTest.java`](../../../buildSrc/src/test/java/io/mazewall/build/RootVerificationFunctionalTest.java) supply the plugin implementation metadata used by the isolated offline fixture. Its fixture requests versionless Kotlin, Detekt, and ktlint plugin IDs through the locked TestKit plugin-under-test metadata, with an empty plugin repository, `--offline`, and real Java/Kotlin, Detekt, SpotBugs, coverage, and failure-control work.

These are build/test-fixture changes. They do not add a runtime dependency or change production APIs. The plugin-resolution fixture does **not** prove versioned root catalog alias resolution or a completely cold execution-artifact closure.

## Focused local results

| Check | Result | Limit |
|---|---|---|
| Core configuration-cache regression (`GREEN002`) | Two actual `:enforcer:test --rerun` invocations succeeded: 5m19s for the initial store and 23s for reuse. The run log records “Configuration cache entry stored” and subsequent reuse. The selected XML has 16 tests, 0 skips, 0 failures/errors; the exercised class verifies seven distinct role entries and executes the child. | This is focused local evidence, not new GitHub CI or the full build. The first-run XML was not separately retained. |
| Build-logic offline plugin fixture (`GREEN002`) | Actual fixture run exited 0 after 8m28s. It used an empty plugin repository and `--offline`, performed real Java/Kotlin compilation, Detekt, SpotBugs, leaf-test and coverage controls, and 14 triage cases. | The fixture consumed a separate read-only dependency cache. It does not establish a fully cold Detekt/KtLint engine, JUnit, or JaCoCo artifact graph; it used versionless fixture plugin IDs and does not prove normal versioned alias resolution. |
| Current offline full quality gate | **Passed** in full-incremental proposal 002; remote PR checks remain pending. | This is a local full build against the recorded cached state and snapshot; it does not close the cold offline fixture requirement or qualify fresh remote CI. |

Independent review found the `@Classpath` location-normalization concern corrected: all three JAR inputs and runtime files use absolute path sensitivity, consistent with the emitted canonical classpath. The source snapshot identities were:

- `enforcer/build.gradle.kts`: `365a36dd5140d5d41afbc797f5b56476627c52f7693e2fc2f63b8a84005002ca`
- `ManagedFixtureClasspathArgumentProvider.java`: `167b4e23904260f41129975a69fc400d296e0f3e8525415f166f7e62dac7920f`
- Core GREEN002 before/after source manifest: `4e49abdcfc93eab7f44b01af04652cc1817b787db2ab558dee64d5b3b1610d40`
- Build-logic GREEN002 before/after source manifest: `b2aa1338501bea92db4b0123cdaf6cf9e05ab876083baa3061e11bbc22d84ec0`

## Retained failures and required follow-up

The earlier PR 567 workflow run 43653792767 remains a failed baseline: the core job reported configuration-cache serialization problems; Build Logic Validation could not resolve `dev.detekt:dev.detekt.gradle.plugin:2.0.0-alpha.3` in the isolated offline fixture; and the security job's `dependencyCheckUpdate` failed on upstream NVD HTTP 503 responses before the vulnerability scan ran. That is not a vulnerability finding, and it produced no scan result. Do not bypass or weaken the security workflow. A fresh PR run must produce a real security scan result.

Earlier packets also remain unchanged: the first cold-red attempt consumed a warm read-only cache and was not a valid cold red; metadata GREEN001 failed on a versioned TestKit resolver conflict; and core GREEN001 failed. GREEN002 does not rewrite those outcomes. The remaining issue acceptance requires a genuinely cold offline fixture that executes the real analysis graph using the currently declared plugin, engine, JUnit, and JaCoCo inputs, followed by the full quality gate and fresh remote PR checks. Keep issue 858 OPEN until then.

## Receipt provenance

Raw runner logs and local manifests remain workspace-only, ignored receipts, not clean-checkout dependencies. Their hashes are preserved here for audit:

- Core GREEN002 log: `1e375b3954b8de41101132b051e16657b36bf9f331d6a5d6b9ffaa3fb6c07207`; exit receipt: `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`.
- Build-logic GREEN002 log: `b422d038e7fb0b15c56a14a78ed3911bb62eeb7f0bd9daf99e9f1202faae57e9`; exit receipt: `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`.
- Independent focused cross-audit: `f2f3d57da508d9392269b7d1eb82b884edd58814d113586a32782414a1fcf909`.

The original 4,823-test Tier E full-gate figure in the pause handoff is historical evidence for its own source snapshot only, not verification of this CI correction.

## Follow-up verification — GREEN006 and PR run 43663609264

The next fixture iteration, GREEN006, passed with the declared `gradle/verification-metadata.xml` copied unchanged into the fixture. `VerificationWorkflowInputFunctionalTest` now exercises the production JavaExec registration and verifies that changing only the checksum input invalidates the task before the existing workflow cases. The actual suite passed.

GREEN006 used an explicit online preparation phase to resolve 101 selected existing fixture configurations under the repository's root verification-metadata scope; all subsequent fixture analyses and controls ran offline. Each run created a fresh synthetic project with an empty plugin repository, while reusing the same task-owned TestKit cache. No new cache copy or altered metadata, catalog, or lockfile was used. Real Java/Kotlin, Detekt, SpotBugs, coverage checks, and 14 triage cases ran. This is bounded evidence for that prepared-offline fixture path; it is not proof that a newly empty TestKit cache can execute without the explicit preparation phase.

The run restored the repository-declared JaCoCo 0.8.14 and ktlint 1.7 inputs after earlier attempts drifted to incompatible defaults. The retained preparation failures remain part of the history:

- preparation 001 failed its unused ABI-compatibility version listing;
- preparation 002 exposed the missing required checksum input in the toy fixture;
- preparation 003 strictly rejected JaCoCo default 0.8.15;
- preparation 004 strictly rejected ktlint default 1.5;
- preparation 005 correctly refused to proceed when offline ktlint 1.7 artifacts were unavailable, rather than copying a cache.

These failed packets are not erased or relabeled by GREEN006.

PR run 43663609264 no longer failed on the Detekt plugin marker; it next exposed that Detekt CLI was unavailable to the offline fixture. The local explicit preparation path addresses that dependency gap, but the PR run remains red. Its core cache host segment passed cache priming and reuse, while the kernel lane reported 19/244 failures requiring a separate CI environment investigation. Do not skip or waive those checks. In the security job, the NVD database update again failed with HTTP 503 and the vulnerability scan was skipped; this is an upstream availability failure, not a clean scan or a vulnerability finding.

A fresh remote PR run after the corrections and the full quality gate are still **pending**. Keep issue 858 OPEN; do not claim all CI is green. No security workflow, credential handling, production behavior, or security policy was weakened.

Workspace-only receipt provenance (not portable inputs): `buildlogic-preparation-green006/build.log` SHA-256 `b1553df2fc218e1d3fd1d5503c758e21064bf33643bdf590b97ceafc18216886`; its before/after source manifests match, SHA-256 `cfd533ee1125577da867a58a24ecbd6cc68ed95bb90474814409c192b7b0d424`. The PR run receipts are `core-followup.log` SHA-256 `9d74035b2f242cd0cb1e41f3bd6ee3059f8756d162110ba12071886e1771341d` and `security-followup.log` SHA-256 `703ceef6569f8c3c59b9907fe2c12d7b5d9e269c2efeb066c6597eaab59df04a` under the existing ignored CI evidence directory.

## Verification follow-up — focused lane fixtures and PR run 43669767931

The first current-interface fixture attempt, Stage 1 proposal 001, remains a preserved RED policy-fixture attempt. Its successor, Stage 1 proposal 002, passed the corrected local checks: the Python runner-validation suite ran 11 tests; five actual JUnit selector modes (default, `ALL`, `BASELINE`, `CONFINED`, and invalid input) exercised fresh-JVM partitioning; and the CI policy functional checks passed their negative controls. The logged Gradle task ended successfully with 4 executed and 11 up-to-date tasks. The default remains `ALL`; `BASELINE` and `CONFINED` partition the three complete affected classes without altering their test-source annotations.

The standalone Stage 2 failure-triage fixture used an online preparation phase for existing dependencies with the repository's verification metadata unchanged, then ran the fixture assertions offline. Its 14 executable triage controls passed; Gradle reported 1 task executed and 13 up-to-date. This is prepared-offline fixture evidence; it does not establish execution from an empty dependency cache.

The private confined lane then ran all three complete classes locally and produced fresh XML: `ConfinedHelperReadinessIntegrationTest` 8 tests, `ConfinedHelperProcessIntegrationTest` 26, and `OwnedProcFdReopenKernelTest` 8; total 42, with zero skips, failures, or errors. The runner and XML validator exited 0, the container exited 0 and was retired, and the bounded log reported no overflow. The same source and index manifests were measured before and after execution: source digest `c7429c243c7d922a3e63946c5f41b4cb497909a6f3c30d330a6375f6f2ec92e0` and index digest `4f32259b373cbd9bdb926c0641df73207fb1da10e5e34b394defba13761c984d`. The approved Moby v0.2.4 seccomp baseline plus only the `pidfd_getfd` argument-2-equals-zero allowance was used (profile SHA-256 `94b42d4064fc993773f44488ea55406017ab1ef5a0ee5ccc3e5fd19859763136`). Image identity was `sha256:8047b78d7af43aa5fa9dcfd61d5bbdcf47044e0a154221b2e8a3ba28c4fa02e1`. The case log identifies kernel `7.2.11-x64v3-xanmod1`, JDK `25.0.5+8-LTS`, amd64, and workload UID/GID 1000. `/dev` and `/dev/null` both reported device 108, and the owned proc-fd `NO_XDEV` case passed. This is local runner evidence; its UID/GID 1000 does not qualify the CI caller identity 1001. The existing ordinary Podman profile is unchanged. The private lane is required alongside all existing required CI jobs; the local pass does not qualify that remote CI lane.

PR run 43669767931 remains red. The core host/configuration-cache segment, optional checks, mutation job, and Go job passed, while the kernel integration job reported 19 failures among 244 tests with 2 skipped (including confined-helper failures and the proc-fd `/dev/null` `EXDEV` case). Build Logic Validation passed its main fixture and reached failure triage, where the isolated offline fixture could not resolve `org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0`. The security job restored its prior snapshot successfully, but NVD requests returned HTTP 503 through the retry attempts and `dependencyCheckUpdate` failed before scanning. This is no vulnerability finding and no scan result. Preserve the security gate and obtain a normal scan result on a fresh run.

A one-shot full quality gate and fresh remote CI satisfying both the ordinary and required private kernel lanes remain pending. Keep issues 858 and 787 OPEN. The broader Tier E handoff remains paused, Live guards remain closed, and issue 752 remains unknown; these PR results do not change those states.

Workspace-only receipt provenance (not portable inputs): Stage 1 proposal 002 build log SHA-256 `7c83890e6e0c47fed21e0db69f1a8bc24146eefe932a9fe419ca5ab33d8bb86b`; matching before/after source manifest SHA-256 `5f8c84aa72ed8b80b72aba5e0f5963aa7accf88cef607296848be7a0d3c14a59`. Stage 2 proposal 001 build log SHA-256 `08ba7ee4b7f790976ba1877ef56032ce239a89e4ff153391ae30de570066429b`; matching before/after source manifest SHA-256 is the same. The private runner log SHA-256 is `a598af46368f677b2c5b8a3284f2f99ad96edb3882eb65e4ed867970e056867c`; validator log `4518811cea8097c48f12c589e8dab7bdf9a30ad64e3f9f75f6c6d1df7dd0aa8f`; XML manifest `36203ed46fe54932046f8644339cdbe8023414c4d0243c903704d802417670c1`. PR 43669767931 receipt hashes: `buildlogic-third-ci.log` `cf51b7dd4ce6f08a010e1c74f58f0c6fa9d3851d3e856b8493384f6758446211`; bounded core excerpt `core-132676999648-bounded.txt` `24812de486cfe695612ce560ad56a073d5b3233c1ad9a079763b1c39a3ca5b09`; bounded security excerpt `security-132676999671-bounded.txt` `c71dc85432abdd7a54ca9833fac91e0baab649df643ac46d283a5267f410ef05`. The full run logs and generated XML remain ignored workspace receipts.

## Current local full-gate checkpoint — 2026-11-15

The first full-incremental attempt (proposal 001) failed only on ktlint formatting in two `enforcer` Kotlin script lines. The whitespace-only correction then passed the focused `:enforcer:ktlintKotlinScriptCheck` and `ConfinedHelperLaneFunctionalTest` checks in kernel-lint proposal 001. The earlier RED receipt is retained; it is not rewritten as a pass.

Full-incremental proposal 002 completed `./gradlew build --offline --no-daemon --console=plain --no-parallel --max-workers=1 --configuration-cache --configuration-cache-problems=fail --info -I .superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/ci-pr567-20261115-001/full-incremental-proposal001/fresh-jvm.gradle`. The included init script changes only the `:enforcer:integrationTestFreshJvm` prewarm timeout to 15 seconds; other defaults remain unchanged. The build reported **BUILD SUCCESSFUL** in 30m25s, 248 actionable tasks (135 executed, 8 from cache, 105 up-to-date), and stored the configuration cache. `buildLogicCheck` and its included `buildSrc` `VerificationActionsTest`, `:enforcer:integrationTest`, `:enforcer:integrationTestFreshJvm`, Detekt ratchet, and coverage work actually executed. The separate focused lint task passed; `:enforcer:test` and script lint in this full run were up-to-date. An independent inventory reports 678 XML files / 4,744 test cases / 25 skipped / 0 failures and errors, but includes up-to-date outputs and is not an all-fresh test count. The executed enforcer integration task reported 244 tests / 2 skipped / 0 failures; the executed fresh-JVM task reported 120 / 1 skipped / 0 failures.

The full run's start, logger, and build exits were zero. Its final container state was exited with PID 0, no OOM, no dead/error state, and the retirement receipt passed. Source comparison was empty; before/after source manifest SHA-256 was `b4eb89531df5819aa830f07fd4b0d60e71dd9af9bdf4585b71d3171620386eb7`. The raw `.git/index` digest matched before and after at `4f32259b373cbd9bdb926c0641df73207fb1da10e5e34b394defba13761c984d`. This gate includes cached/up-to-date results and does not establish a fresh execution of every test output.

The full log still records three profiler `socket` `ENOSYS` results and three suppressed-directory errors. Issue 752's individual-process, file-descriptor, and pump-retirement state remains UNKNOWN; these diagnostics are not treated as harmless or as resolved by the passing build. The aggregate historical 5,187-test mixed count is not applied to this snapshot.

The latest remote PR run remains 43669767931 and red: its kernel lane had 19 failures / 244 tests / 2 skipped, Build Logic Validation lacked offline `kotlinx-serialization-json:1.9.0` in failure triage, and the security scan was blocked by NVD HTTP 503 after successful snapshot restoration. A new push and fresh remote CI, including both required kernel lanes and a real security scan result, remain pending. Issues 858 and 787 remain OPEN; the cold offline dependency-closure evidence for issue 858 also remains outstanding. Keep the broader Tier E handoff paused and Live guards closed.

Workspace-only provenance (not portable inputs): full build log SHA-256 `14a9cca673cd12d6f7d64d55c8e5e0a2fc459055493bfbe3a8e9f6fb5f5cb966`; full-incremental proposal 002 source-before/source-after manifest SHA-256 `b4eb89531df5819aa830f07fd4b0d60e71dd9af9bdf4585b71d3171620386eb7`; raw before/after `.git/index` SHA-256 `4f32259b373cbd9bdb926c0641df73207fb1da10e5e34b394defba13761c984d`. The independent audit inventory receipt is `full-incremental-proposal002-independent-audit.md`, SHA-256 `be0a9b18dbcdee3163237ef7d372a313146f2da0ec0845cd10b05e2557359b92`. The retained full001 RED, focused whitespace-fix checks, and full002 logs remain ignored workspace receipts.
