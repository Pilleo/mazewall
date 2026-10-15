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
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierESplitFixtureController.kt"
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
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEContainerRun.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureController.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureLifecycle.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierENativeFixtureBuild.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEContainerRunTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureControllerTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureLifecycleTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierENativeFixtureBuildTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEContainerizedRunnerOwnershipTest.kt"
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
  - "docs/internals/evidence/tier-e-kotlin-boundary-20260925/README.md"
  - "docs/internals/evidence/tier-e-kotlin-boundary-20260925/SHA256SUMS"
  - "docs/internals/evidence/tier-e-kotlin-boundary-20260925/rootful-docker-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-boundary-20260925/absent-podman-junit.xml"
  - "docs/internals/evidence/tier-e-kotlin-boundary-20260925/fault-replay.json"
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

## 2026-10-06 operator-requested prevention follow-up

During integration-foundation verification, `./scripts/run_tests.sh` refused
`profiler-native/build`: the directory was owned by `0:0`, while the caller was
`1000:1000`. Its directory and CMake-cache modification dates were 20 September;
this is evidence of pre-existing generated-output contamination, not proof that
the current caller-mapped test runner produced it.

Recovery used the existing local `mazewall-test-runner` image, network disabled,
and a bind mount of only the generated directory. A root container changed its
ownership to `1000:1000` without deleting outputs. The subsequent full Docker
`test integrationTest integrationTestFreshJvm` gate passed in 6m21s. Recovery
is not the prevention design and must not become an automatic test prerequisite.

The operator explicitly requests prevention across every supported producer:

1. Reconcile the existing protections before adding another ownership mechanism:
   caller UID/GID and mapping canary in `run_containerized_tests.sh`, caller-owned
   build in `buildSrc/.../TierENativeBuildTask.java`, and the native-cache/run-volume
   boundaries in `TierENativeFixtureBuild`, `TierERootfulGateRunner`,
   `TierERootfulPhaseProbe`, `TierENativeScenarioRunner`, `TierETrustworthyRunner`,
   and `TierEBenchmarkBundleBuilder`. Identify the producer of any new fault from
   command/run receipts rather than assuming an old directory was just created.
2. Require compiler/Gradle processes writing host bind mounts to execute as the
   invoking UID/GID. Local source mounts for root compilation must be read-only;
   root-owned mutable CMake/Gradle state stays in an engine-owned volume. Copy
   reviewed artifacts and diagnostics out with a caller-owned host writer.
   Keep root privileges for the kernel acquisition phase, not host compilation.
3. Exercise each producer with real local rootful Docker: successful build,
   warm-cache repeat, nonzero build exit, timeout, interrupted waiter, and forced
   container death. After every outcome, verify host output/cache ownership and
   that a normal host incremental compile still succeeds. Use an outside-checkout
   ownership marker to reject an incorrect UID/user-namespace mapping before any
   writable checkout/cache mount. Include generated `profiler-native/build` in
   the checked output closure, even when the preferred cache lives elsewhere.
4. Preserve the existing preflight refusal and add post-run ownership verification
   on both success and failure. Preserve the original command failure and attach
   any ownership/export failure as secondary evidence. Do not repair ownership
   with recursive `chown` in EXIT cleanup or suppress the refusal to get green.
5. Run the focused ownership/lifecycle tests, a disposable CMake builder fixture,
   then the full container gate using local Docker. Record owner census before
   and after and archive this issue only when every supported write path has
   independently observed evidence; rootless or unavailable endpoints cannot
   satisfy the rootful gate.

This is planned follow-up work; the incident recovery and passing container gate
do not establish that all ownership-producing paths are already corrected.

---
<!-- id: issue-20260925-141818-005-prevent-root-owned-outputs-from-containerized-test  file: issue-20260925-141818-005-prevent-root-owned-outputs-from-containerized-test.md -->
