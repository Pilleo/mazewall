---
title: "Unify build-owned verification and remove abandoned repository tooling"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [":platform", ":enforcer", ":profiler", ":portals:codegen", ":portals:runtime", ":portals:worker"]
target_files:
  - ".gitignore"
  - "AGENTS.md"
  - "build.gradle.kts"
  - "gradle/verification-metadata.xml"
  - "buildSrc"
  - ".github/workflows/ci.yml"
  - "scripts"
  - "src/consumerVerification"
  - "src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/ContainedExecutorWrapTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
  - "portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
  - "portals/worker/src/test/kotlin/io/mazewall/portal/worker/PortalWorkerStartupTest.kt"
  - "tests/consumers"
  - "benchmarks/integration-boundaries"
  - "profiler/hotspot-agentless"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssets.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotProtocol.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotAssetsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotProtocolTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotChildFixture.kt"
  - "profiler/build.gradle.kts"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools"
  - "profiler/src/test/resources/tier-e"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt"
  - "profiler/src/tooling"
  - "experiments/hotspot-agentless"
  - "docs/internals/evidence"
  - "docs/internals/designs/core/testing-strategy.md"
  - "docs/internals/backlog/README.md"
  - ".agents/hooks.json"
  - ".grok/hooks/mazewall_guards.json"
target_symbols: []
open_questions: false
---

# 🔴 [Severity: HIGH]: Unify build-owned verification and remove abandoned repository tooling

**Context:**
The operator approved the nine-task repository cleanup plan on 2026-10-14.
The audit baseline is `96789e513136948338870ce7255fdec14e22c162`. Both lint and coverage
wrappers can return success after failed Gradle invocation; CI's host gate omits
the complete analyzer set. Consumer/native verification is spread over Python
and shell implementations, experiment links require physical materialization,
and 79 raw archives occupy 235,535,341 tracked bytes.

Execution is inline in the assigned isolated `cleanup/build-owned-verification`
branch. The approved operator-local plan is
`docs/superpowers/plans/2026-10-14-repository-cleanup.md`.

## Investigation

The unchanged suppression script already rejects 83 annotation entries in 46
source files because its nine recorded approvals reference old paths/line numbers
and omit current sites. Preserve its failure evidence while migrating the contract; no blanket
allowlist approval or silent bypass is authorized.
The operator subsequently approved retiring this obsolete line-number audit and
approval file, retaining configured Detekt and the unchanged warning ratchet as
enforcement. No annotation-remediation scope or replacement approval task is needed.
The cold mandatory TestKit baseline also reproduced a delayed-event fixture
timeout: its 10-second finalizer handshake runs while the collector recompiles
against the modified fixture buildSrc. Prepare that dependency before withholding
Test events; retain the actual asynchronous wait-entry checks.

Task 4 migration parity passes both P617 consumer JDK rows and all four raw payloads,
with identical descriptive summaries and all 320 named corruption outcomes.
On 2026-10-14 the operator approved the checksum-policy scope extension. All 47
missing entries were independently reviewed and added, preserving every existing
approval and strict verification; see [the resolved checksum follow-up](resolved/issue-20261014-162921-507-approve-missing-checksum-metadata-for-existing-opt.md).
Task 5's complete real isolated Koog bootstrap and both generated package variants
now pass. Its five Koog Python files are retired after mapped behavior acceptance.
Finished Boot/native and measurement migration remain unfinished; those four Python
files retain their current acceptance role until equivalent behavior passes.
The operator instructed consistent maintenance of the inherited verification policy.
The [Spring checksum follow-up](resolved/issue-20261014-180023-219-review-missing-checksum-metadata-for-existing-opti.md)
is resolved with independent artifact review and strict producer/native acceptance.
Task5's finished Boot external review and all14 measurement collision checks now
pass against the private current-source qualification fixture; all9 consumer Python
files are retired after mapped acceptance. Main/operator branches and historical
P617 publication/locks remain unchanged.

Task6's thirteen source links are materialized into a physical standalone native
snapshot. Task7's retained preparation, assets, distribution, footprint,
benchmark/candidate and inline shell Python now have Go owners. Native packaging
and oracle parity precede Python retirement. Fresh standalone export verifies the
owning/upstream race suites, native construction and actual preflight refusals.
The final rebuild-image lookup changes the real source fingerprint to
`618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c`;
current JVM asset/protocol pins follow the rebuilt bundle while historical
projections retain their original bytes and are refused for current production
admission. All three generated native artifacts remain byte-identical; nineteen
selected Kotlin checks include real owned native capture, with zero skips.
Actual current Gradle verify/distribution/install and source/cache guards pass.
Task8's generated installed CLI replaces all23 Tier E script-tree files. Thirty-six
selected host checks and the actual installed-launcher 72-path rootful case pass
without failures/errors/skips. Cold bootstrap propagates failure before invoking
the builder, uses separate owned project caches, and retains prepared-native
verification and bundle cache ownership. Current v3 bundle fingerprints bind
permission bits as well as bytes; historical v2 evidence and replay remain intact.
The operator clarified on 2026-10-15 that obsolete benchmark bundles are local
data and must not be published. This supersedes the proposed release and remote
custody requirements. Removed the HTTP downloader, tests, Gradle task and URL
manifest. All79 payloads/235,535,341 bytes are verified in local sibling storage
`jseccomp-benchmark-archives/` before removal from the cleanup checkout. Original
reports/checksums/manifests/member indexes remain unchanged. Two existing archive-
dependent tests now use only their12 exact small input files as test resources
(95,069 bytes), preserving their assertions without an archive or tar dependency.
The full included-module quality gate passes (`./gradlew build`,
`run-a5404747f4b5`, 2026-10-15). It first exposed six unnecessarily public JUnit
temporary-directory getters and a synthetic child fixture still carrying the
historical provider pin. Narrowed fixture visibility and rebound only synthetic
child input to the current pin; focused regressions and unchanged analyzers pass.
That earlier full-build result precedes the local-only archive simplification.
The revised archive-independent regression cases (four tests), mandatory buildSrc
checks and source/cache-policy guards pass (`run-06644570f073`). The subsequently
observed worker-liveness assertion is resolved as a test-observation race: pool
termination precedes JVM thread-exit hooks. The test now performs a bounded join
and still requires physical worker death; production containment is unchanged.
See [the resolved investigation](resolved/issue-20261015-094330-565-investigate-worker-liveness-after-sandboxdispatche.md).
All19 focused checks pass without skips, and the current full included-module
build passes (`run-74490f4ff1a6`, 2026-10-15). The cleanup is ready for scoped review.

The initial cleanup is committed as `696ba718` and integrated into the existing
`fast-master` review branch. PR531's clean CI exposed two additional source/build
boundary gaps: eager optional-native task realization in the host aggregate and
missing integration/consumer scripts in the rootful source archive. Both have
meaningful RED/GREEN controls and a successful follow-up full build
(`run-bd93ef1797a4`); the default-branch merge awaits the published CI result.

Follow-up `80b67b5e` passes the earlier configuration/source boundaries in CI.
CI then exposes an independent evidence-test admission timing assumption, fixed
with a second bounded queued admission while retaining one physical worker and
all denial/evidence assertions. All33 owned-runtime cases pass. The rootful job
now reaches and honestly refuses its unchanged hardware-specific performance
limits. The operator subsequently approved local-only benchmarks and removing
that job from required PR CI; see [the resolved decision](resolved/issue-20261015-115813-512-decide-required-ci-treatment-of-hardware-specific-.md).
The local CLI's performance limits are unchanged. Build/security/kernel correctness
gates remain required, and their outstanding failures still block the merge.
The next correctness follow-up fixes startup accounting for workers never created
after early failure, observes explicit execution end in the one-permit metadata
test, and retains real child stderr for the unresolved CI-only portal startup
refusal. All34 owned-runtime cases, the real portal launch fixture, and the full
included-module build pass locally (`run-d231eece1093`). CI must verify the
published follow-up; missing worker readiness remains a failure.
CI at `50a70bc4` confirms all34 owned-runtime cases pass and now rejects only the
JVM-floor helper's overly narrow JDK connection-refusal spelling. The helper
accepts the two exact ordinary refusal strings while actual acquired EPERM/EACCES
still propagate. Five isolated floor cases and the full included-module build
pass (`run-c492ffafc9d7`); the next remote result remains authoritative for merge.
CI run38748172105 on the compatible Ubuntu26.04 host now passes host correctness,
including all13 real portal-codegen cases and the previously failing runtime/floor
suites. It then exposes a bare-selector error in the kernel container invocation;
the workflow now selects the exact declared root `:kernelCheck` aggregate, retaining
all intended kernel coverage gates and assembly. No failed test was skipped.
The kernel gate now configures and runs; CI's remaining launch-profile failure
retains ownership and rejects startup but its expected specific cause is missing.
The fixture now includes the actual exception in its assertion message. Fifty
negative local acquisitions pass; CI evidence is needed before changing lifecycle
behavior. No generic-failure acceptance or cleanup weakening was introduced.
The admission-test candidate passes the full included-module build on Ubuntu
OpenJDK25.0.4.1 (`run-b7e52d1e97d0`). Local GraalVM mapping-denial worker exits
remain independently recorded; the test-only change does not mask that issue.

**Needed:**
1. Make Gradle authoritative for host/full lint, test, coverage, and build guards.
2. Port the source guard and all actual regression cases into Kotlin build logic.
3. Remove abandoned agent-memory/analysis tools and root patch/diagram scratch.
4. Migrate real packaged-consumer checks and portable shared `flock` ownership;
   rename the 14 diagnostic probes to `rejected-api` without weakening checks.
5. Move consumer acceptance to Kotlin using disposable source/deployment fixtures.
6. Materialize the 13 experiment source links into a standalone committed snapshot.
7. Move retained native preparation/assets/benchmark tooling into Go.
8. Replace Tier E launch scripts with the Gradle-generated Kotlin CLI and retire
   unsupported historical scripts through pinned replay.
9. Move obsolete benchmark archives to verified local storage outside Git. Keep
   reports and necessary regression inputs; remove publishing/downloader machinery.
10. Run focused behavioral checks, the final full build, and backlog validation.

## Side effects

Preserve the POM/RefactorFirst, orchestrator-specific files, public API/ABI,
candidate617 identities and eight strict locks, seven exploit-demo Python files,
and unrelated operator work. Keep native/external acquisition uncached. Do not
push to the default branch or rewrite history. Benchmark bundles stay local;
verify their local copies before removing the worktree payloads.

---
<!-- id: issue-20261014-083804-704-unify-build-owned-verification-and-remove-abandone  file: issue-20261014-083804-704-unify-build-owned-verification-and-remove-abandone.md -->
