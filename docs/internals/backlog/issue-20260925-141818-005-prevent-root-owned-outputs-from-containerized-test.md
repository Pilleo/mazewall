---
title: "Prevent root-owned outputs from containerized tests and Tier E gates"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "testing"
target_modules: [":profiler"]
target_files:
  - "scripts/run_containerized_tests.sh"
  - "scripts/run_tier_e_rootful_gate.sh"
  - "scripts/run_tier_e_trustworthy_benchmark.sh"
  - "scripts/run_tier_e_split_privilege_fixture.sh"
  - "infra/dev/compose.yml"
  - "infra/dev/README.md"
  - ".devcontainer/devcontainer.json"
  - ".github/workflows/ci.yml"
  - "src/sharedTest/kotlin/io/mazewall/testing/containers/ContainerEndpointDiscovery.kt"
  - "src/sharedTest/kotlin/io/mazewall/testing/containers/EnabledIfRootfulContainerAvailable.kt"
  - "src/sharedTest/kotlin/io/mazewall/testing/containers/TierEContainerRun.kt"
  - "src/sharedTest/kotlin/io/mazewall/testing/containers/TierEFixtureController.kt"
  - "src/sharedTest/kotlin/io/mazewall/testing/containers/TierENativeFixtureBuild.kt"
  - "src/test/kotlin/io/mazewall/testing/containers/ContainerEndpointDiscoveryTest.kt"
  - "src/test/kotlin/io/mazewall/testing/containers/ContainerizedTestRunnerOwnershipTest.kt"
  - "src/test/kotlin/io/mazewall/testing/containers/TierEContainerRunTest.kt"
  - "src/test/kotlin/io/mazewall/testing/containers/TierEFixtureControllerTest.kt"
  - "src/test/kotlin/io/mazewall/testing/containers/TierENativeFixtureBuildTest.kt"
  - "profiler/build.gradle.kts"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierERecoveryReaderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetExitCoordinatorTest.kt"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/README.md"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/SHA256SUMS"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/archived-fault-parity.json"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/rootful-docker-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/rootful-condition-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/absent-podman-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/short-ring-drop/coverage-oracle.json"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/short-ring-drop/coverage-manifest.json"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/short-ring-drop/coverage-verdict.json"
  - "docs/internals/evidence/tier-e-kotlin-fault-and-ownership-20260925/short-ring-drop/terminal-report.json"
  - "docs/internals/evidence/tier-e-kotlin-teardown-20260925/README.md"
  - "docs/internals/evidence/tier-e-kotlin-teardown-20260925/SHA256SUMS"
  - "docs/internals/evidence/tier-e-kotlin-teardown-20260925/rootful-docker-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-teardown-20260925/absent-podman-junit.xml"
target_symbols: ["ContainerEndpointDiscovery", "RootfulContainerExtension"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prevent root-owned outputs from containerized tests and Tier E gates

**Context:**
Rootful containers wrote into read-write checkout and normal host Gradle cache as root, breaking local Kotlin compilation and pre-commit checks.

**Needed:**
1. Verify local developer-run UID/GID mapping before mounting the checkout
   read-write; refuse uncertain or remote mappings. Keep container Gradle and
   NVD caches in separate caller-owned locations, keyed for reuse without
   exposing the developer's normal `~/.gradle` to a container.
2. Stage current tracked/untracked source into engine-owned storage for a
   privileged Tier E gate, use a separate persistent Gradle volume, and
   export validated artifacts as the invoking caller. The benchmark runner
   must also use an engine-managed artifact volume across its build, benchmark,
   perf, and allocation phases rather than root-writing a host artifact bind
   and repairing ownership in EXIT traps. Scope cleanup to the run's own
   container/workspace; preserve failures and logs.
3. Make rootful Docker/Podman JUnit tests run only when a rootful server is
   reachable and otherwise report a skip, with a local ownership/timeout/
   failure integration probe. Rootless Podman developer builds retain keep-ID.
4. Update Compose, devcontainer, CI cache paths and ownership gate; replace
   after-the-fact recursive `chown` with a check that fails if root-owned
   output was produced.
5. Give Kotlin ownership of the PID-bound split fixture's exact target and
   collector names so a killed controller cannot leave privileged containers
   running. Preserve the only evidence-bearing volume/container on failed
   export, validate the oracle and terminal fault contracts independently,
   and resolve default engine contexts before admitting a local `/proc`-bound
   fixture.

**Verification:** Rootful Docker small build and warm-cache repeat must
leave caller-owned checkout/cache files. Rootful Podman integration execution
remains unverified until an endpoint is available; malformed/absent endpoints
must skip JUnit tests, not produce a false privileged result. An admitted
container failing after startup must fail the test and export caller-owned
diagnostics. No long Tier E measurement rerun is implied.

---
<!-- id: issue-20260925-141818-005-prevent-root-owned-outputs-from-containerized-test  file: issue-20260925-141818-005-prevent-root-owned-outputs-from-containerized-test.md -->
