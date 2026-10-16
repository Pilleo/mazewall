# Tier E Package-First Acceptance Plan

**Status:** Executed once on frozen candidate `07dcdb22` under the operator's instruction. The checked source-bound [receipt](../../evidence/tier-e-package-first-07dcdb22-complete-20260929/README.md) records all reached stages as `COMPLETE`; the checklist below preserves the approved pre-execution procedure, not a request for another run.

**Goal:** Complete one exact-source acceptance sequence without confusing diagnostic-built and Gradle-built native artifacts.

**Candidate:** Freeze `07dcdb220847044ce83f24f22eec5eb374743548` in an independent clean clone. Subsequent plan/evidence documentation commits do not move this candidate. Any result qualifies only the frozen candidate, not a later HEAD.

**Basis:** [Previous binary-identity stop](../../evidence/tier-e-2a886864-native-identity-stop-20260929/README.md). Phase 1 passed timing, but the plan named a different full Debug binary. Identical `.text` does not satisfy the full-file hash contract. The preparation error was selecting a hash from another build path before preparing the package actually consumed by acceptance.

## Fixed constraints

- Gradle 9.6.1; rootful Docker `unix:///var/run/docker.sock`.
- Kernel `7.0.0-34-generic`, config SHA-256 `e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180`.
- Image `sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`; verify its selected tag before and after each stage.
- Native CPU set `0,2`; correctness workload CPU 0, collector CPU 2, noise CPU 3.
- Unchanged v2 comparable phase baseline, SHA-256 `f3f614584f5d0cd37549059fbdb195195247c5cd8bc590abbf01b05a532e9216`; unique stack limit 2,307,381 ns. Diagnostic capture clocks disabled.
- No reuse of old acceptance samples, no replacement of slow samples, no threshold adjustment. Preserve every historical terminal and operator-owned worktree change.
- `policy_eligibility=false`, `REJECT_DEFAULT_PROMOTION`; Podman remains deferred.

## 1. Prepare artifacts before declaring a measurement attempt

- [ ] Verify the clean clone's full revision, runtime source digest, kernel/config, image ID and CPUs. Keep evidence outside the source clone.
- [ ] Resolve the native phase, unaudited correctness and pinned-v2 aggregate profiles without starting their acquisition tasks. Check their hashes and confirm the image IDs agree.
- [ ] Build **Debug and Release** packages through the existing Gradle tasks `:profiler:tierENativeBuild` and `:profiler:tierETrustworthyNativeBuild`, using the exact intended profiles and Docker endpoint. These are compilation tasks, not timing or kernel qualification.
- [ ] Verify every file against each package manifest. Record package manifests, actual full library hashes, workload-class hashes, native-source digest, compiler, JDK, CMake, architecture, build type and USDT setting. Debug and Release are separate identities.
- [ ] Treat `15914500...` as a previous observation to investigate against, not an assumed hash. Never import `9dafc149...` from the direct-CMake diagnostic lane as the acceptance identity.
- [ ] Inspect how the phase/correctness/aggregate controllers consume these packages. Confirm the phase and aggregate select the same Debug package, correctness selects the prepared Release package, and identities are checked before CTest or workload release. If a needed prelaunch check is absent, fix/test it before freezing the final candidate; revise the proposed candidate explicitly, then repeat build-only preparation.
- [ ] Verify image admission does not implicitly rebuild the image. Confirm all required host tests/build checks pass for the final code; earlier-source results cannot cover new code changes.

**Exit:** One frozen source, two manifest-verified Gradle packages, and demonstrated prelaunch identity admission. Preparation failure means **not ready**; do not spend a measurement attempt discovering a known package mismatch.

## 2. Seal one new acceptance declaration

- [ ] Write the final candidate revision/digest, actual Debug and Release full hashes, all manifests/profile/baseline hashes, kernel/image/toolchain identities, commands, output directories, stage order and stop rules to a new manifest.
- [ ] Checksum the declaration before any acceptance CTest. Store the prepared binaries with the receipt; verify those exact bytes before every stage. If a build task runs again, compare its complete package to the sealed identity before measuring.
- [ ] Declare three fresh six-sample phase runs, then one five-pair correctness series, then one aggregate. Select **phase run 1** for later aggregate reuse in advance; do not choose whichever receipt looks fastest.
- [ ] Independent prelaunch review: candidate is clean, packages match, required files exist, output directories are fresh, old runners are untouched, and no project build/test is running concurrently with measurement.

**Exit:** A sealed, internally consistent declaration. Do not change source, package hashes or thresholds after the first measurement.

## 3. Run native timing acceptance

- [ ] Execute `:profiler:tierERootfulPhaseProbe` three times in separate fresh output directories, sequentially. Each run contains three full and three unique fresh 100-iteration CTests under the original v2 checker.
- [ ] After each run, verify source/image/package/class/toolchain/CPU identities, all six raw logs, phase verdicts, terminal and owner export before starting the next run.
- [ ] Stop on the first timing or integrity failure; record the actual reason. No fourth run, retry, dropped sample or continuation to correctness.

**Exit:** 3/3 complete phase runs on the sealed Debug identity.

## 4. Run correctness

- [ ] Execute `:profiler:tierENodeNoiseQualification` once with the sealed Release package and original unaudited 10,000-operation/mode, 1,000/s five-pair AB/BA profile.
- [ ] Require all 20 forks to reconcile exact operation IDs and original loss/retention constraints. Verify existing noise evidence according to its actual scope; it proves overlap with each noisy arm's first full-stream fork, not automatically its later unique fork.
- [ ] Stop on the first incomplete pair or identity/export failure; do not retry.

**Exit:** 5/5 pairs and 20/20 exact forks on the same frozen source.

## 5. Run the aggregate once

- [ ] Execute `:profiler:tierERootfulGate` with the sealed v2 pinned-image profile and preselected phase-1 receipt.
- [ ] Require the existing exact-source phase-reuse verifier, full required suite results, native package identity and pinned-image admission/export evidence to pass.
- [ ] Stop on any failure, retain owned recovery artifacts and report which prerequisite or suite failed.

**Exit:** A complete aggregate receipt for this exact source/environment, still policy-ineligible until the separate operator support/promotion decision.

## 6. Seal the outcome and stop

- [ ] Preserve original raw logs, manifests, commands, package bytes and each terminal; independently verify checksum ledgers and host exports.
- [ ] Update the scoped issue and testing guidance with reached stages and precise limitations. Evidence documentation may be committed elsewhere without rebasing or updating the acquisition clone.
- [ ] On success, present the exact candidate for the operator's support decision. On failure, present the bounded finding and one evidence-based next action; do not automatically create another attempt.

No additional A/B/C diagnostic is part of this plan. The completed diagnostic already established probe perturbation; the next question is whether the prepared, uninstrumented Gradle packages meet the existing acceptance contract.
