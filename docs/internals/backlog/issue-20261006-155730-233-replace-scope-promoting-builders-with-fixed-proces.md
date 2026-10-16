---
title: "Replace scope-promoting builders with fixed process and worker construction"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer", "profiler", "demos:cli-demo", "demos:agent-sandbox-demo", "demos:vulnerable-web-app"]
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api"
  - "enforcer/src/test"
  - "enforcer/src/integrationTest"
  - "enforcer/api/enforcer.api"
  - "profiler/src"
  - "demos"
  - "tests/consumers"
  - "GETTING_STARTED.md"
  - "README.md"
  - "enforcer/README.md"
  - "profiler/README.md"
  - "docs/internals/designs/api/enforcer-public-api.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Replace scope-promoting builders with fixed process and worker construction

**Context:**
C1/C2 require imperative and fluent worker rules to agree, explicit filesystem intent, and compile-time exclusion of worker filesystem methods from process construction.

**Needed:**
1. Add parity and negative consumer compile regressions, migrate callers, and remove superseded public construction APIs.

## Implementation evidence — first construction cut

- Base revision `235b1acf`; revised integration tasks C1/C2. Scope remains open until all legacy nested-builder callers are migrated and that path is removed.
- Fixed-scope `ProcessPolicyBuilder`/`WorkerPolicyBuilder` share existing internal definition configuration and compiler. Their explicit list factories require a runtime; allow-list factories include the canonical JVM floor. Worker mutators retain the same object. Boolean mapping/clone/prctl settings assign true and false.
- `FileSystemPolicy` distinguishes unrestricted, active deny-all, and immutable resource sets. Assignment replaces pre-install filesystem state; immutable built snapshots and restrictive intersection semantics remain intact.
- `FixedScopeBuilderTest` initially failed compilation because the canonical contracts were absent. Seven contract cases passed after implementation, including imperative/apply parity, template reconstruction, immutable resource intent, list/runtime/floor behavior, and Boolean assignment.
- Removed `JavaPolicyBuilder` and its top-level builder factories after migrating their Java/Kotlin consumers. The definition-level `PolicyBuilder` is now internal. Legacy nested `Policy.Builder`/list-spec callers remain the next migration slice; no new bridge or alias was introduced.
- Migrated runtime-specific process factories and profiler result/behavior/iterative conversion to fixed-scope builders. Generated profiler snippets use explicit runtime and worker scope even without filesystem observations, matching result-level conversion scope. Two focused DSL regressions were observed red, then the full profiler host suite passed after migration.
- Fresh packaged Java/Kotlin consumers passed. Actual javac fixtures reject process filesystem methods and assigning a worker definition to process scope; unrelated missing-dependency failures cannot satisfy these checks.
- Actual-kernel isolated-JVM `PolicyIntentNativeTest` passed both reconstructed active-empty and explicit canonical deny-all cases.
- Full enforcer/profiler host suites, profiler integration-source compilation, Detekt, and updated enforcer ABI gate passed. Final merge evidence is recorded after its run.
- Initial merge gate rejected eight new spread-operator warnings. Removed temporary array allocations in path adapters and JVM-floor setup; the warning inventory and suppressions were not expanded. Focused builder tests and the warning ratchet then passed.
- `./gradlew build` passed: 272 tasks, 97 executed, 175 up-to-date. `./gradlew integrationTest integrationTestFreshJvm` passed, then `MAZEWALL_CONTAINER_ENGINE=docker MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock ./scripts/run_tests.sh` passed in 1m7s.
- Final actual-kernel report: `PolicyIntentNativeTest` has two tests, zero failures/errors/skips. This does not establish optional collector acceptance or a complete integration release.

## Subsequent list-facade and production-caller migration

- Base revision `d41452e7`. Removed `PolicyLists`, `DenyListSpec`, `AllowListSpec`, and the forwarding `Policy` companion list methods after migrating all source callers. `PolicyMode` remains the same inspectable enum in its own file.
- Canonical allow-list floor tests continue to simulate the emitted BPF program: every JVM-floor syscall retains its original verdict (including restricted unsafe prctl), the operator's requested CONNECT is allowed, and unlisted SOCKET remains denied. Existing restrictive-composition tests now construct policies directly with fixed-scope builders.
- Migrated CLI, agent, and vulnerable-web production policy construction to fixed-scope builders. Worker execution uses worker scope; explicit process bootstrap uses process scope. Existing executable-mapping settings are preserved: HotSpot where the demo already enabled executable mappings, and the existing CLI W^X setting remains explicit.
- `./gradlew :enforcer:test :profiler:test :enforcer:checkKotlinAbi :demos:cli-demo:test :demos:agent-sandbox-demo:test :demos:vulnerable-web-app:test` passed.
- `./gradlew build` passed: 265 tasks, 23 executed, 242 up-to-date. Its initial run rejected import ordering and method-chain formatting in the changed web-demo files; only those formatting defects were corrected, and the focused lint gate then passed.
- Remaining nested `Policy.Builder` callers are largely host/kernel/compiler test fixtures. Their migration and retirement of the nested construction path remain open; canonical artifact serialization is a separate unfinished C1 closure.

## Final C2 construction retirement

- Base revision `1e0d862f`. Migrated every remaining host, kernel, compiler, profiler, and CLI fixture to fixed-scope construction, then removed `Policy.Builder`, `Policy.builder()`, and `Policy.threadLocalBuilder()` entirely. No forwarding or binary-only compatibility declarations remain.
- Explicit `NATIVE_IMAGE` initialization preserves the former raw builders' W^X default in compatibility-sensitive compiler/kernel fixtures; fixtures that selected HotSpot or enabled executable mappings still do so. This is preserved test configuration, not a new runtime-support claim.
- Filesystem-bearing construction uses worker scope. Existing process-installation and compiled-policy type checks still compile. Replaced the obsolete copy-promotion assertion with a frozen-worker-snapshot assertion; packaged negative compilation continues to prove process builders cannot acquire filesystem state.
- The randomized restrictive-composition generator now uses the same worker builder as application consumers. Existing syscall verdict, native installation, lifecycle, and permission assertions remain in their owning tests.
- Updated primary construction examples and the ABI snapshot to the retired surface. The source census finds no retired construction calls/types; only historical test names retain the old naming.
- `./gradlew :enforcer:test :profiler:test :enforcer:updateKotlinAbi` passed. All migrated host/integration sources compiled before full checks.
- `./gradlew build integrationTest integrationTestFreshJvm` passed: 272 tasks, 93 executed, 179 up-to-date. Full caller-mapped Docker `test integrationTest integrationTestFreshJvm` gate passed in 1m19s.
- Fresh staged Java/Kotlin consumers, actual javac process/worker scope rejection, and the existing Callable/Supplier ambiguity characterization passed with updated dependency locks. Actual-kernel deny-all report: two tests, zero failures/errors/skips.
- Inspected the actual staged JAR: no class entries remain for `Policy$Builder`, `JavaPolicyBuilder`, `PolicyLists`, `DenyListSpec`, or `AllowListSpec`; retirement is not merely an ABI-text change.
- C2 implementation is complete; this issue stays open for repository review. Canonical artifact serialization remains a separate C1/C6 closure, and required installation/runtime ownership remain C3/C7 work.

---
<!-- id: issue-20261006-155730-233-replace-scope-promoting-builders-with-fixed-proces  file: issue-20261006-155730-233-replace-scope-promoting-builders-with-fixed-proces.md -->
