---
title: "Move active Tier E experiment controllers and remaining analysis to typed Kotlin tooling"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["profiler", "scripts"]
target_files:
  - "scripts/run_tier_e_split_privilege_fixture.sh"
  - "scripts/run_tier_e_split_privilege_fixture_legacy.sh"
  - "scripts/run_tier_e_split_coverage_matrix.sh"
  - "scripts/run_tier_e_split_negative_matrix.sh"
  - "scripts/run_tier_e_split_matched_benchmark.sh"
  - "scripts/run_tier_e_hybrid_fault_matrix.sh"
  - "scripts/run_tier_e_hybrid_live_comparison.sh"
  - "scripts/run_tier_e_trustworthy_benchmark.sh"
  - "scripts/run_tier_e_sustained_capacity.sh"
  - "scripts/run_tier_e_coverage_benchmark.sh"
  - "scripts/run_tier_e_node_noise_probe.sh"
  - "scripts/run_tier_e_checks.sh"
  - "scripts/run_tier_e_release_benchmark.sh"
  - "scripts/run_tier_e_matched_flavour_benchmark.sh"
  - "scripts/compare_tier_e_stack_transports.sh"
  - "scripts/check_tier_e_benchmark.sh"
  - "scripts/run_tier_e_mapped_drain_microbenchmark.sh"
  - "scripts/run_tier_e_decoder_microbenchmark.sh"
  - "scripts/build_tier_e_benchmark_bundle.sh"
  - "scripts/tier_e_coverage_summary.mjs"
  - "scripts/tier_e_coverage_stack_identity.mjs"
  - "scripts/summarize_tier_e_coverage.mjs"
  - "scripts/run_tier_e_rootful_gate.sh"
  - "scripts/tier_e_tools"
  - "profiler/build.gradle.kts"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/EnabledIfTierERootfulAvailable.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierERunConfigurationTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierEFixtureController.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureControllerTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierEFixtureLifecycle.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierEContainerRun.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierENativeFixtureBuild.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierESplitFixtureController.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierESplitFixtureControllerTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureLifecycleTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEFixtureVerdictTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEFixtureEnvelopeTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierERealPidReuseProbeTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierESplitOperationVerifierTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierESplitForkRowTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEHybridFaultRowsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEHybridLiveRowsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierESplitMatchedSummaryTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERunConfiguration.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEExecutionProfile.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierECoverageSummary.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierECoverageStackIdentity.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierECoverageReport.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkBundleManifest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkBundleFingerprint.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkBundleBuilder.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEExperimentProfile.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEExperimentCapacityProfile.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEProfileExperimentRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthySettings.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyGuestCommand.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyGuestMain.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyContainerOwner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthySettlement.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEAllocationProbe.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthySettlementTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEAllocationProbeTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustedArtifactExport.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyResultVerifier.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthySourceState.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEPerfAnalysisPlan.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEPerfGuestMain.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyPerfEvidence.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyAllocationEvidence.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulSourceTransfer.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateProfile.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulPhaseProbe.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulPhaseEvidence.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulPhaseReuse.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateResultVerifier.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkPhaseGate.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESustainedCapacitySettings.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESustainedCapacityStage.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESustainedCapacityRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierECoverageExperiment.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENodeNoiseSample.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENodeNoiseTarget.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENodeNoiseProbe.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENativeScenarioSummary.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENativeScenarioRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERingMicrobenchmarkRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEChecksRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEActiveEntrypointPolicy.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkBundleManifestTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkBundleFingerprintTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkBundleBuilderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkBundleBootstrapTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateProfileTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateRunnerTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulPhaseReuseTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthySettingsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierECoverageSummaryTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierECoverageStackIdentityTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierECoverageReportTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierECoverageMatrixRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEFaultMatrix.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierELiveMatrix.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEMatchedMatrix.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEDirectContainerArm.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEArmAttempt.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkOperationAcceptance.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEMatrixTerminal.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEMatrixProvenance.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEMatchedMatrixTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEDirectContainerArmTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEArmAttemptTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkOperationAcceptanceTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEMatrixTerminalTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEMatrixProvenanceTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENegativeMatrix.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEFixtureVerdict.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEFixtureEnvelope.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERealPidReuseProbe.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESplitOperationVerifier.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESplitForkRow.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEHybridFaultRows.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEHybridLiveRows.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESplitMatchedSummary.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEToolsMain.kt"
  - "src/sharedTest/kotlin/io/mazewall/testing/containers/ContainerEndpointDiscovery.kt"
  - "src/sharedTest/kotlin/io/mazewall/testing/containers/EnabledIfRootfulContainerAvailable.kt"
  - "src/test/kotlin/io/mazewall/testing/containers/ContainerEndpointDiscoveryTest.kt"
  - "config/testing/tier-e/smoke.json"
  - "config/testing/tier-e/coverage.json"
  - "config/testing/tier-e/fault-recovery.json"
  - "config/testing/tier-e/long-matched.json"
  - "config/testing/tier-e/real-pid-reuse.json"
  - "config/testing/tier-e/capacity.json"
  - "config/testing/tier-e/node-noise.json"
  - "config/testing/tier-e/trustworthy.json"
  - "config/testing/tier-e/rootful-gate.json"
  - "docs/internals/designs/profiler/tier-e-testing-migration.md"
  - "docs/internals/evidence/tier-e-pinned-native-comparison-20260927/README.md"
  - "docs/internals/evidence/tier-e-pinned-native-comparison-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-pinned-native-comparison-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-pinned-native-comparison-20260927/raw-ctest.tar.gz"
  - "docs/internals/evidence/tier-e-pinned-rootful-gate-20260927/README.md"
  - "docs/internals/evidence/tier-e-pinned-rootful-gate-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-pinned-rootful-gate-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-pinned-rootful-gate-20260927/raw-gate.tar.gz"
  - "docs/internals/evidence/tier-e-typed-capacity-20260927/README.md"
  - "docs/internals/evidence/tier-e-typed-capacity-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-typed-capacity-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-typed-capacity-20260927/raw-capacity.tar.gz"
  - "docs/internals/evidence/tier-e-clean-node-noise-20260927/README.md"
  - "docs/internals/evidence/tier-e-clean-node-noise-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-clean-node-noise-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-clean-node-noise-20260927/raw-diagnostics.tar.gz"
  - "docs/internals/evidence/tier-e-separated-phase-gate-20260927/README.md"
  - "docs/internals/evidence/tier-e-separated-phase-gate-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-separated-phase-gate-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-separated-phase-gate-20260927/raw-receipts.tar.gz"
  - "docs/internals/evidence/tier-e-kotlin-perf-allocation-20260926/README.md"
  - "docs/internals/evidence/tier-e-kotlin-perf-allocation-20260926/SHA256SUMS"
  - "docs/internals/evidence/tier-e-kotlin-perf-allocation-20260926/manifest.json"
  - "docs/internals/evidence/tier-e-kotlin-real-pid-reuse-20260926/README.md"
  - "docs/internals/evidence/tier-e-kotlin-real-pid-reuse-20260926/SHA256SUMS"
  - "docs/internals/evidence/tier-e-kotlin-real-pid-reuse-20260926/manifest.json"
  - "docs/internals/evidence/tier-e-kotlin-real-pid-reuse-20260926/raw-fixture.tar.gz"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/README.md"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/SHA256SUMS"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/rootful-docker-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/absent-podman-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/short-row-parity.json"
  - "docs/internals/evidence/tier-e-kotlin-matrix-20260925/README.md"
  - "docs/internals/evidence/tier-e-kotlin-matrix-20260925/SHA256SUMS"
  - "docs/internals/evidence/tier-e-kotlin-matrix-20260925/parity.json"
  - "docs/internals/evidence/tier-e-kotlin-matrix-20260925/rootful-docker-junit.xml"
target_symbols:
  - "TierERunConfiguration"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Move active Tier E experiment controllers and remaining analysis to typed Kotlin tooling

**Context:**
Active Tier E Bash controllers and Node matrix summaries still interpret configuration, orchestrate privileged fixtures, and make evidence decisions; profile-based Kotlin configuration and helper migration is only a first slice.

**Needed:**
 1. Complete Kotlin ownership of the split fixture's container/process lifecycle, registration, metadata, fault scenarios, and evidence export. The current JSON profiles and Kotlin verdict/probe/row tools are a first slice, not completion.
 2. Replace the active long/short/live/fault Bash experiment controllers, Node matrix summaries, and remaining active analysis paths with Kotlin. Keep paced long measurements explicit, outside the default build.
 3. Preserve archived Python/Node reproduction commands, all historical evidence and checksum ledgers. Revalidate output and rejection parity against retained artifacts before switching each caller.
 4. Qualify rootful Podman on a real endpoint. Until then mark runtime parity unverified; never claim policy eligibility or reverse REJECT_DEFAULT_PROMOTION.

**2026-09-26 progress:** The active launchers, split fixture, matrices, bundle,
capacity, coverage, rootful gate, and trustworthy perf/allocation controllers
now delegate substantive decisions and lifecycle to Kotlin. Replayed historical
rows, short rootful Docker tests, native phase v2, owned perf/allocation, and
real disposable-guest host-PID reuse have retained evidence linked from the
[migration design](../designs/profiler/tier-e-testing-migration.md). The
Kotlin node-noise controller passed an opt-in-audited one-pair rootful diagnostic
with exact accounting and an observed attachment identity; the unaudited
multi-pair qualification remains `INCOMPLETE` because target controls also
intermittently miss declared operations. See [the sealed diagnostic](../evidence/tier-e-kotlin-node-noise-diagnostic-20260926/README.md)
and [the still-open pre-admission issue](issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md).
Podman runtime parity is operator-deferred and **unverified**. Do not close this
issue on the Docker-only migration receipts or treat the one-pair diagnostic
as a decision-quality capacity/noise comparison.

**Final rootful gate blocker:** The final diagnostics source revision
`d82ca4b9` passed `./gradlew build` and its rootful Docker native/profiler
checks, but two bounded complete rootful gate attempts rejected the unchanged
historical `GetStackTrace` phase limit (4,676,424 ns, then 2,743,788 ns).
Both retained stopped exact-name runners and volumes under
`/tmp/tier-e-kotlin-final-rootful-gate-resumed-1790463269` and
`/tmp/tier-e-kotlin-final-rootful-gate-retry-1790463583` with
`INCOMPLETE`, policy-ineligible terminals and recovery receipts. The earlier
sealed passing v2 sample is historical; a final passing clean gate is not
claimed. Neither threshold nor baseline was changed to clear this blocker.

**2026-09-27 execution improvements:** The rootful gate no longer passes
`--rerun-tasks`; pure compilation/tooling output remains Gradle-cacheable,
while live kernel integration tasks are explicitly non-cacheable. A phase-only
Docker controller separates native build reuse from fresh CTest acquisition,
records source/image/toolchain/binary identities, always samples both modes,
and retains exact owned resources when a historical bound fails. A separate
replay task verifies saved samples and was observed `UP-TO-DATE` on its second
unchanged invocation. The second keyed native-only run reused the first run's
exact binary SHA-256 while recording new run IDs and different medians; the
source-dirty results are diagnostic only. Strict experiment JSON profiles
replace active trustworthy and node-noise setting env-vars at the new Gradle
entrypoints; historical env launchers remain. A predetermined, source-pinned,
interleaved previous/current comparison and a passing final aggregate rootful
gate are still required before unblocking the handoff.

**2026-09-27 clean pinned follow-up:** The prior/current source-pinned,
interleaved native comparison is retained at
[pinned phase evidence](../evidence/tier-e-pinned-native-comparison-20260927/README.md).
Every individual CTest passed and the two revisions used identical native
agent/workload class bytes. Unrestricted placement also failed for the old
revision; explicit CPUs `0,1` completed all six planned prior/current runs
under the unchanged 125% historical bounds. The later clean committed
`de268c9e` [full rootful receipt](../evidence/tier-e-pinned-rootful-gate-20260927/README.md)
passed 39 native CTests, the profiler/rootful/fresh-JVM suites and both phase
modes with `policy_eligibility=false`. That receipt predates the separate
`b8901ed7` capacity-profile and bundle-cache isolation slice. New version-two
JSON experiment profiles explicitly select Docker image and measured CPUs;
the [bounded typed capacity smoke](../evidence/tier-e-typed-capacity-20260927/README.md)
completed twice after separating the bundle bootstrap and nested Gradle
project caches from the operator's locked checkout. The 1,000/s one-second
pilot is not a decision ceiling. The clean `b8901ed7` default five-pair
[node-noise attempt](../evidence/tier-e-clean-node-noise-20260927/README.md)
stopped on pair 1 when unique mode missed five of 10,000 declared IDs; the
same-source unaudited no-noise control and one-pair audit-enabled diagnostic
completed, but neither upgrades the default `INCOMPLETE` result. The
post-slice aggregate gate needs its own result. Podman remains
operator-deferred and this issue stays open.

**2026-09-27 final rootful split:** The first clean post-capacity gate timed
out one five-second four-thread fresh-JVM test under whole-container CPU
pinning; its identical retry passed the live JVM suite but failed both
`GetStackTrace` bounds. A child-only phase-first attempt also rejected the
unchanged historical phase bound. Each failure retained an exact stopped
runner, volume, `INCOMPLETE` terminal and raw evidence. The new
[source-bound separated phase receipt](../evidence/tier-e-separated-phase-gate-20260927/README.md)
for clean committed `ae38c8ab` passed three fresh CTests per native mode.
The full rootful gate admitted it only after matching revision/source digest,
image, exact native agent and Java workload SHA-256s, compiler/JDK, CPUs and
replaying all six raw samples. The separate full gate then passed 39 native
CTests and all required live profiler/fresh-JVM suites with `COMPLETE`,
`policy_eligibility=false`. This is not a Podman or unaudited multi-pair
node-noise qualification, and `REJECT_DEFAULT_PROMOTION` remains in force.

---
<!-- id: issue-20260925-211445-014-move-active-tier-e-experiment-controllers-and-rema  file: issue-20260925-211445-014-move-active-tier-e-experiment-controllers-and-rema.md -->
