# PR 567 build-fixture corrections: focused verification status

**Status (2026-11-15): focused local checks passed; the issue remains open.** The bounded changes address the enforcer configuration-cache capture and the offline build-logic fixture's plugin supply path. No fresh GitHub PR result or one-shot full quality gate has qualified this snapshot yet. The work does not change production runtime or sandbox behavior.

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
| Full quality gate and remote PR checks | **Pending.** | Do not reuse the earlier Tier E full gate as evidence for these corrections. |

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
