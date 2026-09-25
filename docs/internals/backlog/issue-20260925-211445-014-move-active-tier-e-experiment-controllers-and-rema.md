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
  - "scripts/run_tier_e_split_coverage_matrix.sh"
  - "scripts/run_tier_e_split_matched_benchmark.sh"
  - "scripts/run_tier_e_hybrid_fault_matrix.sh"
  - "scripts/run_tier_e_hybrid_live_comparison.sh"
  - "scripts/run_tier_e_trustworthy_benchmark.sh"
  - "scripts/run_tier_e_rootful_gate.sh"
  - "scripts/tier_e_tools"
  - "profiler/build.gradle.kts"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/EnabledIfTierERootfulAvailable.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierERunConfigurationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureController.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureControllerTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureLifecycle.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureLifecycleTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEFixtureVerdictTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEFixtureEnvelopeTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierERealPidReuseProbeTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierESplitOperationVerifierTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierESplitForkRowTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERunConfiguration.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEFixtureVerdict.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEFixtureEnvelope.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERealPidReuseProbe.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESplitOperationVerifier.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESplitForkRow.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEToolsMain.kt"
  - "src/sharedTest/kotlin/io/mazewall/testing/containers/ContainerEndpointDiscovery.kt"
  - "src/sharedTest/kotlin/io/mazewall/testing/containers/EnabledIfRootfulContainerAvailable.kt"
  - "src/test/kotlin/io/mazewall/testing/containers/ContainerEndpointDiscoveryTest.kt"
  - "config/testing/tier-e/smoke.json"
  - "config/testing/tier-e/coverage.json"
  - "config/testing/tier-e/fault-recovery.json"
  - "config/testing/tier-e/long-matched.json"
  - "docs/internals/designs/profiler/tier-e-testing-migration.md"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/README.md"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/SHA256SUMS"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/rootful-docker-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/absent-podman-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-config-transition-20260925/short-row-parity.json"
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

---
<!-- id: issue-20260925-211445-014-move-active-tier-e-experiment-controllers-and-rema  file: issue-20260925-211445-014-move-active-tier-e-experiment-controllers-and-rema.md -->
